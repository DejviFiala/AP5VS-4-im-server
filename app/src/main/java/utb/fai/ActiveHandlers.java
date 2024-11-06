package utb.fai;

import java.util.*;
import java.util.concurrent.*;

public class ActiveHandlers {
    private HashSet<SocketHandler> activeHandlersSet = new HashSet<>();
    private ConcurrentHashMap<String, SocketHandler> userNameMap = new ConcurrentHashMap<>();

    /**
     * sendMessageToAll - Pole zprávu vem aktivním klientùm kromì sebe sama
     * 
     * @param sender  - reference odesílatele
     * @param message - øetìzec se zprávou
     */
    synchronized void sendMessageToGroup(SocketHandler sender, String message) {
        for (SocketHandler handler : activeHandlersSet) {
            if (handler != sender && !Collections.disjoint(handler.getGroups(), sender.getGroups())) {
                if (!handler.messages.offer(message)) {
                    System.err.printf("Client %s message queue is full, dropping the message!\n", handler.getUserName());
                }
            }
        }
    }

    /**
     * add pøidá do mnoiny aktivních handlerù nový handler.
     * Metoda je sychronizovaná, protoe HashSet neumí multithreading.
     * 
     * @param handler - reference na handler, který se má pøidat.
     * @return true if the set did not already contain the specified element.
     */
    synchronized boolean add(SocketHandler handler) {
        return activeHandlersSet.add(handler);
    }

    /**
     * remove odebere z mnoiny aktivních handlerù nový handler.
     * Metoda je sychronizovaná, protoe HashSet neumí multithreading.
     * 
     * @param handler - reference na handler, který se má odstranit
     * @return true if the set did not already contain the specified element.
     */
    synchronized boolean remove(SocketHandler handler) {
        return activeHandlersSet.remove(handler);
    }

    synchronized boolean isUserNameAvailable(String userName) {
        return !userNameMap.containsKey(userName);
    }

    synchronized void addUserName(String userName, SocketHandler handler) {
        userNameMap.put(userName, handler);
    }

    synchronized void removeUserName(String userName) {
        userNameMap.remove(userName);
    }

    SocketHandler getHandlerByUserName(String userName) {
        return userNameMap.get(userName);
    }
}
