package utb.fai;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class SocketHandler {
    Socket mySocket;
    String clientID;
    ActiveHandlers activeHandlers;
    ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(20);
    CountDownLatch startSignal = new CountDownLatch(2);
    OutputHandler outputHandler = new OutputHandler();
    InputHandler inputHandler = new InputHandler();
    volatile boolean inputFinished = false;

    private String userName = null;
    private Set<String> groups = ConcurrentHashMap.newKeySet();

    public SocketHandler(Socket mySocket, ActiveHandlers activeHandlers) {
        this.mySocket = mySocket;
        clientID = mySocket.getInetAddress().toString() + ":" + mySocket.getPort();
        this.activeHandlers = activeHandlers;
    }

    public String getUserName() {
        return userName;
    }

    public Set<String> getGroups() {
        return groups;
    }

    class OutputHandler implements Runnable {
        public void run() {
            OutputStreamWriter writer;
            try {
                startSignal.countDown();
                startSignal.await();
                writer = new OutputStreamWriter(mySocket.getOutputStream(), StandardCharsets.ISO_8859_1);
                writer.write("Welcome! Please enter your username (no spaces):\n");
                writer.flush();
                while (!inputFinished) {
                    String m = messages.take();
                    writer.write(m + "\r\n");
                    writer.flush();
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            } finally {
                try {
                    mySocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    class InputHandler implements Runnable {
        public void run() {
            try {
                startSignal.countDown();
                startSignal.await();
                BufferedReader reader = new BufferedReader(new InputStreamReader(mySocket.getInputStream(), StandardCharsets.ISO_8859_1));
                OutputStreamWriter writer = new OutputStreamWriter(mySocket.getOutputStream(), StandardCharsets.ISO_8859_1);

                // Setting username
                while (userName == null) {
                    String nameInput = reader.readLine();
                    if (nameInput == null) {
                        break;
                    }
                    nameInput = nameInput.trim();
                    if (!nameInput.isEmpty() && !nameInput.contains(" ")) {
                        synchronized (activeHandlers) {
                            if (activeHandlers.isUserNameAvailable(nameInput)) {
                                userName = nameInput;
                                writer.write("Your username has been set to: " + userName + "\n");
                                writer.flush();
                                activeHandlers.addUserName(userName, SocketHandler.this);
                                groups.add("public");
                                writer.write("You have been automatically joined to the group 'public'.\n");
                                writer.flush();
                            } else {
                                writer.write("This username is already taken, please choose another.\n");
                                writer.flush();
                            }
                        }
                    } else {
                        writer.write("Invalid username. Please try again.\n");
                        writer.flush();
                    }
                }

                if (userName == null) {
                    inputFinished = true;
                    messages.offer("Connection closed.");
                    return;
                }

                activeHandlers.add(SocketHandler.this);

                String request = "";
                while ((request = reader.readLine()) != null) {
                    request = request.trim();
                    if (request.isEmpty()) {
                        continue;
                    }

                    if (request.startsWith("#")) {
                        // Command processing
                        if (request.startsWith("#setMyName ")) {
                            String newName = request.substring(11).trim();
                            if (!newName.isEmpty() && !newName.contains(" ")) {
                                synchronized (activeHandlers) {
                                    if (activeHandlers.isUserNameAvailable(newName)) {
                                        activeHandlers.removeUserName(userName);
                                        userName = newName;
                                        activeHandlers.addUserName(userName, SocketHandler.this);
                                        writer.write("Your username has been changed to: " + userName + "\n");
                                        writer.flush();
                                    } else {
                                        writer.write("This username is already taken.\n");
                                        writer.flush();
                                    }
                                }
                            } else {
                                writer.write("Invalid username.\n");
                                writer.flush();
                            }
                        } else if (request.startsWith("#sendPrivate ")) {
                            String[] parts = request.split(" ", 3);
                            if (parts.length >= 3) {
                                String targetUserName = parts[1];
                                String message = parts[2];
                                SocketHandler targetHandler = activeHandlers.getHandlerByUserName(targetUserName);
                                if (targetHandler != null) {
                                    String formattedMessage = "[" + userName + "] >> " + message;
                                    if (!targetHandler.messages.offer(formattedMessage)) {
                                        System.err.printf("Client %s message queue is full, dropping the message!\n", targetUserName);
                                    }
                                    writer.write("Private message sent to user " + targetUserName + ".\n");
                                    writer.flush();
                                } else {
                                    writer.write("User " + targetUserName + " is not online.\n");
                                    writer.flush();
                                }
                            } else {
                                writer.write("Invalid command format. Usage: #sendPrivate <username> <message>\n");
                                writer.flush();
                            }
                        } else if (request.startsWith("#join ")) {
                            String groupName = request.substring(6).trim();
                            if (!groupName.isEmpty()) {
                                groups.add(groupName);
                                writer.write("You have joined the group: " + groupName + "\n");
                                writer.flush();
                            } else {
                                writer.write("Invalid group name.\n");
                                writer.flush();
                            }
                        } else if (request.startsWith("#leave ")) {
                            String groupName = request.substring(7).trim();
                            if (!groupName.isEmpty()) {
                                if (groups.remove(groupName)) {
                                    writer.write("You have left the group: " + groupName + "\n");
                                    writer.flush();
                                } else {
                                    writer.write("You are not a member of the group: " + groupName + "\n");
                                    writer.flush();
                                }
                            } else {
                                writer.write("Invalid group name.\n");
                                writer.flush();
                            }
                        } else if (request.equals("#groups")) {
                            String groupList = String.join(",", groups);
                            writer.write("You are a member of groups: " + groupList + "\n");
                            writer.flush();
                        } else {
                            writer.write("Unknown command.\n");
                            writer.flush();
                        }
                    } else {
                        // Sending message to groups
                        String formattedMessage = "[" + userName + "] >> " + request;
                        activeHandlers.sendMessageToGroup(SocketHandler.this, formattedMessage);
                    }
                }
                inputFinished = true;
                messages.offer("OutputHandler, wake up and die!");
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            } finally {
                activeHandlers.remove(SocketHandler.this);
                if (userName != null) {
                    activeHandlers.removeUserName(userName);
                }
                try {
                    mySocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
