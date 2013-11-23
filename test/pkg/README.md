Integration Test for Container, Assembly and HCD Packaging
==========================================================

This directory contains standalone applications for testing the Container, Assembly and HCD components and is based on
the document "OSW TN009 - TMT CSW PACKAGING SOFTWARE DESIGN DOCUMENT".

Requirements: The Akka ZeroMQ support currently requires ZeroMQ version 2.2.
The Scala code picks up the shared library automatically if it is installed in /usr/lib or /usr/local/lib.
The required library name on the Mac is libzmq.1.dylib.

There is a C/ZeroMQ based low level hardware simulation server called mtserver2 under the "hardware" directory.
That application needs to run first and can be compiled by typing "make" in hardware/src/main/c.

To compile the Scala/Akka code, type "sbt" and then:

* project container1
* dist
* project container2
* dist

For some reason, typing "dist" in the top level csw project appears to compile,
but the resulting installation is missing dependencies.

To run the demo: Open terminal windows or tabs in these directories and run these commands:

* cd hardware/src/main/c; mtserver2
* cd container2/target/bin; ./start
* cd container1/target/bin; ./start
* cd web/pkgtest; play run            # then open http://localhost:9000 in a browser

Currently this is the order in which the applications must be started, but that will be changed in the future
to allow any order.

Enter the values in the form and press Submit. The status of the command is shown below the button and updated
while the command is running.

TODO: Add the ability to pause and restart the queue, pause, cancel or abort a command, etc.
Display a busy cursor and disable the form while the command is running.

The following diagram shows the relationships of the various containers, assemblies and HCDs in this demo:

![PkgTest diagram](doc/PkgTest.jpg)

When the user fills out the web form and presses Submit, a JSON config is sent to the Spray/REST HTTP server
of the Assembly1 command service. It forwards different parts of the config to HCD1 and HCD2, which are in
a different container and JVM, but are registered as components with Assembly1, so that it forwards parts of
configs that match the keys they registered with.

HCD1 and HCD2 both talk to the C/ZeroMQ based hardware simulation code and then return a command status to the
original submitter (Assembly1).

The Play Framework code uses long polling (to the Spray HTTP server) to get the command status and then
uses a websocket and Javascript code to push the status to the web page.

