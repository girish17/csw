Location Service
=====================

The location service keeps track of information about running HCDs and assemblies.
The actors register when starting and other actors can request information about
them.

Starting the Location Service
-----------------------------
To start the location service, run ls/target/bin/start.

Building
--------
The start script is generated by the sbt build when you type "sbt dist" from the top level directory.

Configuring the Host and Port
-----------------------------
You can configure the host and port used by the location service in ls/src/main/resources/reference.conf.
The Akka settings are used when starting the location service, while the csw.location-service settings
are used by other actors to determine the host and port number for the location service.

Using the Location Service
--------------------------

The Assembly and HCD base classes provide shortcuts for registering with the location service and
looking up other services.
