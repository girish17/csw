#!/bin/sh
#
# Creates a single install directory from all the csw stage directories.

dir=../install

hash sbt 2>/dev/null || { echo >&2 "Please install sbt first.  Aborting."; exit 1; }

stage=target/universal/stage

for i in bin lib conf doc ; do
    test -d $dir/$i || mkdir -p $dir/$i
done

sbt compile

# Ignore the error messages generated by unidoc
echo "Generating unified Scala/Java API docs..."
sbt -Dcsw.genjavadoc.enabled=true unidoc > /dev/null 2>&1

sbt publish-local stage

for i in bin lib ; do
    for j in */target/universal/stage/$i/* apps/*/target/universal/stage/$i/* examples/*/target/universal/stage/$i/* ; do
        cp -f $j $dir/$i
    done
done

# XXX FIXME: Get the real alarms.conf file from somewhere
cp alarms/src/test/resources/test-alarms.conf $dir/conf/alarms.conf

cp scripts/*.sh $dir/bin
chmod +x $dir/bin/*.sh

rm -rf $dir/doc/*
cp -r target/javaunidoc/ $dir/doc/java
cp -r target/scala-2.12/unidoc/ $dir/doc/scala

rm -f $dir/bin/*.log.* $dir/bin/*.bat

# create the scalas script, for scala scriping (see http://www.scala-sbt.org/release/docs/Scripts.html)
# Note: This depends on the sbt version declared in project/build.properties (0.13.8).
echo 'sbt -Dsbt.main.class=sbt.ScriptMain "$@"' > $dir/bin/scalas
chmod +x $dir/bin/scalas
