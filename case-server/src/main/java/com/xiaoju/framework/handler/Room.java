package com.xiaoju.framework.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.xiaoju.framework.entity.dto.RecordWsDto;
import com.xiaoju.framework.entity.persistent.TestCase;
import com.xiaoju.framework.entity.xmind.IntCount;
import com.xiaoju.framework.mapper.CaseBackupMapper;
import com.xiaoju.framework.mapper.TestCaseMapper;
import com.xiaoju.framework.service.CaseBackupService;
import com.xiaoju.framework.service.RecordService;
import com.xiaoju.framework.util.BitBaseUtil;
import com.xiaoju.framework.util.TreeUtil;
import org.apache.poi.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.*;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import javax.websocket.Session;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by didi on 2021/3/22.
 */
public abstract class Room {
    private static final Logger LOGGER = LoggerFactory.getLogger(Room.class);

    private final ReentrantLock roomLock = new ReentrantLock();
    private volatile boolean closed = false;

    private static final boolean BUFFER_MESSAGES = true;
    private final Timer messageBroadcastTimer = new Timer();
    private volatile boolean locked = false;
    private volatile String locker = "";
    private static final int TIMER_DELAY = 30;
    private TimerTask activeBroadcastTimerTask;

    private static final int MAX_PLAYER_COUNT = 100;
    public final List<Player> players = new ArrayList<>();
    public final Map<Session, Client> cs = new ConcurrentHashMap<>();

    public static TestCaseMapper caseMapper;
    public static RecordService recordService;
    public static CaseBackupService caseBackupService;

    protected String testCaseContent;
    protected TestCase testCase;

    public void lock() {
        this.locked = true;
    }

    public void unlock() {
        this.locked = false;
    }

    public boolean getLock() {
        return this.locked;
    }
    public String getLocker() {
        return this.locker;
    }
    public void setLocker(String locker) {
        this.locker = locker;
    }
    // id ???????????????case id??????????????????record id
    public Room(Long id) {
        long caseId = BitBaseUtil.getLow32(id);
        if (testCase != null) {
            return;
        }
        testCase = caseMapper.selectOne(caseId);
        String res = testCase.getCaseContent();
        if (StringUtils.isEmpty(res)) {
            LOGGER.error(Thread.currentThread().getName() + ": ??????????????????");
        }
    }

    private TimerTask createBroadcastTimerTask() {
        return new TimerTask() {
            @Override
            public void run() {
                invokeAndWait(new Runnable() {
                    @Override
                    public void run() {
                        broadcastTimerTick();
                    }
                });
            }
        };
    }

    public String getTestCaseContent() {
        return testCaseContent;
    }

    public Player createAndAddPlayer(Client client) {
        if (players.size() >= MAX_PLAYER_COUNT) {
            throw new IllegalStateException("Maximum player count ("
                    + MAX_PLAYER_COUNT + ") has been reached.");
        }
        LOGGER.info(Thread.currentThread().getName() + ": ????????????????????????session id: " + client.getSession().getId());
        Player p = new Player(this, client);

        // ????????????
        broadcastRoomMessage( "?????????????????? " + (players.size() + 1) + "??????????????????" + client.getClientName());

        players.add(p);
        cs.put(client.getSession(), client);

        // ???????????????????????????????????????????????????
        if (activeBroadcastTimerTask == null) {
            activeBroadcastTimerTask = createBroadcastTimerTask();
            messageBroadcastTimer.schedule(activeBroadcastTimerTask,
                    TIMER_DELAY, TIMER_DELAY);
        }

        // ?????????????????????
        String content = String.valueOf(players.size());
        p.sendRoomMessageSync("??????????????????" + content);

        return p;
    }

    protected void internalRemovePlayer(Player p) {

        boolean removed = players.remove(p);
        assert removed;
        
        cs.remove(p.getClient().getSession());
        LOGGER.info(Thread.currentThread().getName() + ": ????????? " + p.getClient().getClientName() + " ?????? session id:" + p.getClient().getSession().getId());

        // ????????????????????????????????????????????????????????????
        if (players.size() == 0) {
            // ????????????
            // todo??? ????????????timer cancel????????????????????????????????????????????????invokeAndWait???????????????
            closed = true;
            activeBroadcastTimerTask.cancel();
            activeBroadcastTimerTask = null;
        }

        // ?????????????????????
//        broadcastRoomMessage("???????????????" + p.getClient().getSession().getId());
    }

    // ????????????????????????????????????buffer???????????????????????????????????????????????????
    protected void broadcastRoomMessage(String content) {
        for (Player p : players) {
            p.sendRoomMessageSync(content);
        }
    }

    private void internalHandleMessage(Player p, String msg,
                                           long msgId) {
        p.setLastReceivedMessageId(msgId);

        //todo: testCase.apply(msg) ?????????????????????.

        broadcastMessage(msg);
    }

    private void internalHandleCtrlMessage(String msg) {
        int seperateIndex = msg.indexOf('|');
        String sendSessionId = msg.substring(0, seperateIndex);

        for (Player p : players) {
            if (sendSessionId.equals(p.getClient().getSession().getId())) {
                p.getBufferedMessages().add("2" + "success");
//                p.sendRoomMessage("2" + "success");
            } else {
                p.getBufferedMessages().add("2" + msg.substring(seperateIndex + 1));
//                p.sendRoomMessage("2" + "lock");
            }
        }
    }

    private void broadcastMessage(String msg) {
        if (!BUFFER_MESSAGES) {
            String msgStr = msg.toString();

            for (Player p : players) {
                String s = String.valueOf(p.getLastReceivedMessageId())
                        + "," + msgStr;
                p.sendRoomMessageSync(s); // ????????????????????????buffer
            }
        } else {
            int seperateIndex = msg.indexOf('|');
            String sendSessionId = msg.substring(0, seperateIndex);
            JSONObject request = JSON.parseObject(msg.substring(seperateIndex + 1));
            JSONArray patch = (JSONArray) request.get("patch");
            long currentVersion = ((JSONObject) request.get("case")).getLong("base");
            testCaseContent = ((JSONObject) request.get("case")).toJSONString().replace("\"base\":" + currentVersion, "\"base\":" + (currentVersion + 1));
            for (Player p : players) {
                if (sendSessionId.equals(p.getClient().getSession().getId())) { //ack??????
                    String msgAck = "[[{\"op\":\"replace\",\"path\":\"/base\",\"value\":" + (currentVersion + 1) + "}]]";
                    p.getBufferedMessages().add(msgAck);
                } else { // notify??????
                    String msgNotify = patch.toJSONString().replace("[[{", "[[{\"op\":\"replace\",\"path\":\"/base\",\"value\":" + (currentVersion + 1) + "},{");
                    p.getBufferedMessages().add(msgNotify);
                }
            }
        }
    }

    private void broadcastTimerTick() {
        // ????????????player????????????
        for (Player p : players) {
            StringBuilder sb = new StringBuilder();
            List<String> caseMessages = p.getBufferedMessages();
//            LOGGER.info("????????????????????????????????????" + caseMessages.size());
            if (caseMessages.size() > 0) {
                for (int i = 0; i < caseMessages.size(); i++) {
                    String msg = caseMessages.get(i);

//                    String s = String.valueOf(p.getLastReceivedMessageId())
//                            + "," + msg.toString();
                    if (i > 0) {
                        sb.append("|");
                        LOGGER.error(Thread.currentThread().getName() + ": client: " + p.getClient().getClientName() + " ???????????????????????????????????? by??????. sb: " + sb);
                    }

                    sb.append(msg);
                }

                caseMessages.clear();

                p.sendRoomMessageSync(sb.toString());
            }
        }

    }

    private List<Runnable> cachedRunnables = null;

    public void invokeAndWait(Runnable task)  {

        // ??????????????????????????????????????????????????????????????????????????????runnable?????????????????????????????????????????????????????????
        if (roomLock.isHeldByCurrentThread()) {

            if (cachedRunnables == null) {
                cachedRunnables = new ArrayList<>();
            }
            cachedRunnables.add(task);

        } else {
            roomLock.lock();
            try {
                // ?????????????????????????????????????????????????????????????????????task.run?????????????????????????????????cache???
                cachedRunnables = null;

                if (!closed) {
                    task.run();
                }

                // ?????????????????????
                if (cachedRunnables != null) {
                    for (Runnable cachedRunnable : cachedRunnables) {
                        if (!closed) {
                            cachedRunnable.run();
                        }
                    }
                    cachedRunnables = null;
                }
            } finally {
                roomLock.unlock();
            }
        }
    }

    public String getRoomPlayersName() {
        Set<String> playerNames = new HashSet<>();
        for (Player p: players) {
            playerNames.add(p.getClient().getClientName());
        }
        return StringUtil.join(playerNames.toArray(), ",");
    }

    public static final class Player {

        /**
         * player?????????room
         */
        private Room room;

        /**
         * room?????????????????????msg id
         */
        private long lastReceivedMessageId = 0;

        private final Client client;
        private final long enterTimeStamp;

//        private final boolean isRecord;

        /**
         * ??????????????????timer???????????????
         */
        private final List<String> bufferedMessages = new ArrayList<>();

        private List<String> getBufferedMessages() {
            return bufferedMessages;
        }

        private Player(Room room, Client client) {
            this.room = room;
            this.client = client;
            this.enterTimeStamp = System.currentTimeMillis();
//            isRecord = client.getRecordId();
        }

        public Room getRoom() {
            return room;
        }

        public Client getClient() {
            return client;
        }

        /**
         * client?????????????????????????????????player
         */
        public void removeFromRoom() {
            if (room != null) {
                LOGGER.info("?????????????????? " + this.getClient().getClientName() +  " ?????????????????????" + String.valueOf(System.currentTimeMillis() - this.enterTimeStamp));
                room.internalRemovePlayer(this);
                room = null;
            }
        }

        private long getLastReceivedMessageId() {
            return lastReceivedMessageId;
        }
        private void setLastReceivedMessageId(long value) {
            lastReceivedMessageId = value;
        }


        /**
         * ????????????????????????????????????????????????????????????players
         *
         * @param msg   ???????????????
         * @param msgId ??????id
         */
        public void handleMessage(String msg, long msgId) {
            room.internalHandleMessage(this, msg, msgId);
        }

        public void handleCtrlMessage(String msg) {
            room.internalHandleCtrlMessage(msg);
        }

        /**
         * ??????room?????????
         * @param content
         */
        public void sendRoomMessageSync(String content) {
            Objects.requireNonNull(content);

            client.sendMessage(content);
        }

        public void sendRoomMessageAsync(String content) {
            Objects.requireNonNull(content);

            this.getBufferedMessages().add(content);
        }
    }
}
