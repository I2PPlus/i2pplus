Stream Echo Demo
================

Terminal 1 (receiver):
  ./samIn.py
  → prints a base64 destination

Terminal 2 (sender):
  ./samOut.py <destination-from-terminal-1>

The sender sends "1" through "10", the receiver echoes each back.

Forward Demo
============

Terminal 1 (forwarder):
  ./samForward.py 25000 myForward
  → prints a base64 destination

Terminal 2 (echo server):
  ./server.py 25000

Terminal 3 (sender):
  ./samOut.py <destination-from-terminal-1> myForward

The forwarder relays the I2P stream to the local echo server.
