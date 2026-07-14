Raw Demo
========

Terminal 1 (receiver):
  ./samIn.py 26000
  → prints a base64 destination, listens on UDP 26000

Terminal 2 (sender):
  ./samOut.py <destination-from-terminal-1> myOut "hello raw"

Forward Demo
============

Terminal 1 (forwarder):
  ./samForward.py 26000 myForward
  → prints a base64 destination, forwards to UDP 26000

Terminal 2 (receiver via netcat):
  nc -u 127.0.0.1 26000

Terminal 3 (sender):
  ./samOut.py <destination-from-terminal-1> myForward "world"
