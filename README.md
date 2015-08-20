TMT Common Software (CSW)
=========================

Common Software is the package of services and infrastructure software that integrates the TMT software systems.

http://www.tmt.org

Build Instructions
------------------

To build, run 'sbt' in the top level directory and type one of the following commands:

* compile - compiles the sources
* test - run the tests
* multi-jvm:test - run tests that use multiple JVMs
* stage - create the standalone apps and test apps (installed in */target/universal/stage/bin)
* publish-local - publish artifacts to the local ivy repository (~/.ivy2)

Commands apply to the entire build unless otherwise specified.
You can narrow the focus to a subproject with the sbt "project" command.
For example: "project cs" sets the current project to "cs". The top level project is "csw".

Creating Installable Packages
-----------------------------

The following sbt commands generate packages that can be installed on various systems:

* universal:packageBin - Generates a universal zip file
* universal:packageZipTarball - Generates a universal tgz file
* debian:packageBin - Generates a deb
* rpm:packageBin - Generates an rpm
* universal:packageOsxDmg - Generates a DMG file with the same contents as the universal zip/tgz.
* windows:packageBin - Generates an MSI

Install script
-----------

The script ./install.sh creates an install directory (../install) containing scripts and all of the required dependencies
for starting the CSW applications.


Projects and Directories
------------------------

* [apps](apps) - contains some standalone applications
* [shared](shared) - shared Scala/Scala.js project, for data transfer objects between client and server
* [cmd](cmd) - the Command Service (for sending commands to HCDs and Assemblies)
* [cs](cs) - the Configuration Service (manages configuration files in Git repos)
* [doc](doc) - for documentation
* [event](event) - the Event Service, based on HornetQ
* [kvs](kvs) - provides key/value store and publish/subscribe features based on Redis
* [loc](loc) - the Location Service (a single actor that that supports registering and finding HCDs and assemblies)
* [log](log) - contains logging related code
* [pkg](pkg) - a packaging layer over the command service that provides HCD and Assembly classes
* [util](util) - for reusable utility code

Applications
-----------

The following standalone applications are installed here:

* [cs](cs) - the config service
* [loc](loc) - the location service

The following applications are defined under ../apps:

* [configServiceAnnex](configServiceAnnex) - an http server used to store and retrieve large files, used by the config server
* [containerCmd](containerCmd) - used to start containers of HCDs or assemblies, based on a given config file (This is not an application, but us used to simplify creating such applications)
* [sequencer](sequencer) - implements the command line sequencer application, which is a Scala REPL shell
* [csClient](csClient) - a command line client to the config service (used in some test scripts)


