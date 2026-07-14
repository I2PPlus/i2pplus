Datagram Demo
=============

Terminal 1 (receiver):
  ./samIn.py 25000
  → prints a base64 destination, listens on UDP 25000

Terminal 2 (sender):
  ./samOut.py <destination-from-terminal-1> myOut "hello"

Forward Demo
============

Terminal 1 (forwarder):
  ./samForward.py 25000 myForward
  → prints a base64 destination, forwards to UDP 25000

Terminal 2 (receiver via netcat):
  nc -u 127.0.0.1 25000

Terminal 3 (sender):
  ./samOut.py <destination-from-terminal-1> myForward "world"
