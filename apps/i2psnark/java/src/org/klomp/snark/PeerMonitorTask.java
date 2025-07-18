/* PeerMonitorTasks - TimerTask that monitors the peers and total up/down speed
   Copyright (C) 2003 Mark J. Wielaard
   This file is part of Snark.
   Licensed under the GPL version 2 or later.
*/

package org.klomp.snark;



/**
 * TimerTask that monitors the peers and total up/download speeds.
 * Works together with the main Snark class to report periodical statistics.
 *
 * @deprecated unused, for command line client only, commented out in Snark.java
 */
@Deprecated
class PeerMonitorTask implements Runnable
{
  final static long MONITOR_PERIOD = 10 * 1000; // Ten seconds.
  private static final long KILOPERSECOND = 1024 * (MONITOR_PERIOD / 1000);

  private final PeerCoordinator coordinator;

  //private long lastDownloaded = 0;
  //private long lastUploaded = 0;

  PeerMonitorTask(PeerCoordinator coordinator)
  {
    this.coordinator = coordinator;
  }

  public void run()
  {
/*****
    // Get some statistics
    int peers = 0;
    int uploaders = 0;
    int downloaders = 0;
    int interested = 0;
    int interesting = 0;
    int choking = 0;
    int choked = 0;

    synchronized(coordinator.peers)
      {
        Iterator it = coordinator.peers.iterator();
        while (it.hasNext())
          {
            Peer peer = (Peer)it.next();

            // Don't list dying peers
            if (!peer.isConnected())
              continue;

            peers++;

            if (!peer.isChoking())
              uploaders++;
            if (!peer.isChoked() && peer.isInteresting())
              downloaders++;
            if (peer.isInterested())
              interested++;
            if (peer.isInteresting())
              interesting++;
            if (peer.isChoking())
              choking++;
            if (peer.isChoked())
              choked++;
          }
      }

    // Print some statistics
    long downloaded = coordinator.getDownloaded();
    String totalDown = DataHelper.formatSize(downloaded) + "B";
    long uploaded = coordinator.getUploaded();
    String totalUp = DataHelper.formatSize(uploaded) + "B";

    int needP = coordinator.storage.needed();
    long needMB = needP * coordinator.metainfo.getPieceLength(0) / (1024 * 1024);
    int totalP = coordinator.metainfo.getPieces();
    long totalMB = coordinator.metainfo.getTotalLength() / (1024 * 1024);

    System.out.println();
    System.out.println("Down: "
                       + (downloaded - lastDownloaded) / KILOPERSECOND
                       + "KB/s"
                       + " (" + totalDown + ")"
                       + " Up: "
                       + (uploaded - lastUploaded) / KILOPERSECOND
                       + "KB/s"
                       + " (" + totalUp + ")"
                       + " Need " + needP
                       + " (" + needMB + "MB)"
                       + " of " + totalP
                       + " (" + totalMB + "MB)"
                       + " pieces");
    System.out.println(peers + ": Download #" + downloaders
                       + " Upload #" + uploaders
                       + " Interested #" + interested
                       + " Interesting #" + interesting
                       + " Choking #" + choking
                       + " Choked #" + choked);
    System.out.println();

    lastDownloaded = downloaded;
    lastUploaded = uploaded;
****/
  }
}
