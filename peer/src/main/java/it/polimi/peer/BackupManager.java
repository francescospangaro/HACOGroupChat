package it.polimi.peer;

import it.polimi.packets.p2p.CloseRoomPacket;
import it.polimi.packets.p2p.MessagePacket;
import it.polimi.peer.utility.ChatToBackup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.PropertyChangeListener;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BackupManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackupManager.class);

    private static final String WAITING_FILENAME = "waitingMessages.dat";
    private static final String WAITING_CLOSE_FILENAME = "waitingCloseMessages.dat";
    public static final String SAVE_DIR = STR."\{System.getProperty("user.home")}\{File.separator}HACOBackup\{File.separator}";
    private final String saveDirectory;
    private final String id;
    private final PropertyChangeListener msgChangeListener;

    public BackupManager(String id, PropertyChangeListener msgChangeListener) {
        this.saveDirectory = SAVE_DIR + id + File.separator;
        this.id = id;
        this.msgChangeListener = msgChangeListener;
    }

    public Set<ChatRoom> getChatsFromBackup() {
        var saveDir = new File(saveDirectory);
        var files = saveDir.listFiles((_, name) -> !name.equals(WAITING_FILENAME) && !name.equals(WAITING_CLOSE_FILENAME));
        Set<ChatRoom> tempChats = ConcurrentHashMap.newKeySet();

        if (files == null)
            return tempChats;

        for (File f : files) {
            try (FileInputStream fileInputStream = new FileInputStream(f);
                 ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream)) {
                ChatToBackup tempChat = (ChatToBackup) objectInputStream.readObject();
                tempChats.add(new ChatRoom(tempChat.name(), tempChat.users(), tempChat.id(), msgChangeListener,
                        tempChat.vectorClocks(), tempChat.waiting(), tempChat.received()));
            } catch (IOException | ClassNotFoundException e) {
                LOGGER.error(STR."[\{this.id}] Error reading file \{f} from backup", e);
            }
        }
        return tempChats;
    }

    public Set<MessagePacket> getWaitingMessagesFromBackup() {
        var file = new File(saveDirectory + WAITING_FILENAME);
        Set<MessagePacket> res = new HashSet<>();
        if (file.exists()) {
            try (FileInputStream fileInputStream = new FileInputStream(file);
                 ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream)) {
                res.addAll((Set<MessagePacket>) objectInputStream.readObject());
            } catch (IOException | ClassNotFoundException e) {
                LOGGER.error(STR."[\{this.id}] Error reading file \{file} from backup", e);
            }
        }
        return res;
    }

    public Set<CloseRoomPacket> getWaitingCloseMessagesFromBackup() {
        var file = new File(saveDirectory + WAITING_CLOSE_FILENAME);
        Set<CloseRoomPacket> res = new HashSet<>();

        if (file.exists()) {
            try (FileInputStream fileInputStream = new FileInputStream(file);
                 ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream)) {
                res.addAll((Set<CloseRoomPacket>) objectInputStream.readObject());
            } catch (IOException | ClassNotFoundException e) {
                LOGGER.error(STR."[\{this.id}] Error reading file \{file} from backup", e);
            }
        }
        return res;
    }

    public void backupChats(Set<ChatRoom> chats) {
        //Create all save directories
        try {
            Files.createDirectories(Paths.get(saveDirectory));
        } catch (IOException e) {
            LOGGER.error(STR."[\{this.id}] Error creating backup folder", e);
        }
        for (ChatRoom c : chats) {
            File backupFile = new File(STR."\{saveDirectory}\{c.getId()}.dat");
            try (FileOutputStream fileOutputStream = new FileOutputStream(backupFile);
                 ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream)) {
                objectOutputStream.writeObject(new ChatToBackup(c));
            } catch (IOException e) {
                LOGGER.error(STR."[\{this.id} Error during backup of chat \{c}", e);
            }
        }
    }

    public void backupWaiting(Set<MessagePacket> waitings) {
        //Create all save directories
        try {
            Files.createDirectories(Paths.get(saveDirectory));
        } catch (IOException e) {
            LOGGER.error(STR."[\{this.id}] Error creating backup folder", e);
        }
        if (!waitings.isEmpty()) {
            File backupFile = new File(STR."\{saveDirectory}\{WAITING_FILENAME}");
            try (FileOutputStream fileOutputStream = new FileOutputStream(backupFile);
                 ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream)) {
                objectOutputStream.writeObject(waitings);
            } catch (IOException e) {
                LOGGER.error(STR."[\{this.id} Error during backup of \{waitings}", e);
            }
        }
    }

    public void backupWaitingClose(Set<CloseRoomPacket> waitingsclose) {
        //Create all save directories
        try {
            Files.createDirectories(Paths.get(saveDirectory));
        } catch (IOException e) {
            LOGGER.error(STR."[\{this.id}] Error creating backup folder", e);
        }
        if (!waitingsclose.isEmpty()) {
            File backupFile = new File(STR."\{saveDirectory}\{WAITING_CLOSE_FILENAME}");
            try (FileOutputStream fileOutputStream = new FileOutputStream(backupFile);
                 ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream)) {
                objectOutputStream.writeObject(waitingsclose);
            } catch (IOException e) {
                LOGGER.error(STR."[\{this.id} Error during backup of \{waitingsclose}", e);
            }
        }
    }

    public void removeChatBackup(ChatRoom toDelete) {
        File chatToDelete = new File(STR."\{saveDirectory}\{toDelete.getId()}.dat");
        // If the file is not deleted it means that it wasn't backed up in the first place
        // We don't care for the deletion outcome
        chatToDelete.delete();
    }


}
