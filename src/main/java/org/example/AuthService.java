package org.example;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface AuthService extends Remote {
    boolean authenticate(String username, String password) throws RemoteException;

    boolean userExists(String username) throws RemoteException;

    boolean createUser(String username, String password) throws RemoteException;

    boolean updatePassword(String username, String newPassword) throws RemoteException;

    boolean deleteUser(String username) throws RemoteException;

    List<String> listUsers() throws RemoteException;
}
