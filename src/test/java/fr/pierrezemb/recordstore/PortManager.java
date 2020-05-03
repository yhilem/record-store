package fr.pierrezemb.recordstore;

import java.net.ServerSocket;
import java.net.Socket;

public class PortManager {
  private static int nextPort = getBasePort();

  public static synchronized int nextFreePort() {
    int exceptionCount = 0;
    while (true) {
      int port = nextPort++;
      try (ServerSocket ss = new ServerSocket(port)) {
        ss.close();
        //Give it some time to truly close the connection
        Thread.sleep(100);
        return port;
      } catch (Exception e) {
        exceptionCount++;
        if (exceptionCount > 5) {
          throw new RuntimeException(e);
        }
      }
    }
  }



  private static int getBasePort() {
    return Integer.valueOf(System.getProperty("test.basePort", "15000"));
  }


  public static boolean listeningPort(int fdbPort) {
    try (ServerSocket s = new ServerSocket(fdbPort)) {
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
