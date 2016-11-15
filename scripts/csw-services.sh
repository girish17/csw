#!/bin/bash
#
# Starts serviced required by CSW and registers them with the location service.
# This script uses the csw trackLocation app to start Redis and register it with the
# Location Service under different names (see below).
#
# Usage is:
#
#  csw-services.sh start     - to start redis and register it for the event, alarm and telemetry services
#  csw-services.sh stop      - to stop redis and unregister from the location service
#
# The services are registered as:
#   "Event Service"
#   "Telemetry Service"
#   "ALarm Service"
#   "Config Service"
#
# Note that the environment variable CSW_INSTALL must be defined to point to the root of the csw install dir
# (This is usually ../install relative to the csw sources and is created by the install.sh script).
#

REDIS_SERVER=/usr/local/bin/redis-server
REDIS_CLIENT=/usr/local/bin/redis-cli

if test ! -x $REDIS_SERVER ; then
    echo "$REDIS_SERVER not found"
    exit 1
fi
if test ! -x $REDIS_CLIENT ; then
    echo "$REDIS_CLIENT not found"
    exit 1
fi

# Set to yes to start the config service
START_CONFIG_SERVICE=yes

REDIS_PORT=7777
REDIS_SERVICES="Event Service,Alarm Service,Telemetry Service"

OS=`uname`

# Dir to hold pid and log files, svn repo
CSW_DATA_DIR=/tmp/csw
test -d $CSW_DATA_DIR || mkdir -p $CSW_DATA_DIR

# Config Service pid and log files
CS_PID_FILE=$CSW_DATA_DIR/cs.pid
CS_LOG_FILE=$CSW_DATA_DIR/cs.log
# Config Service options
CS_OPTIONS="--init --nohttp --noannex"

# Redis pid and log files
REDIS_PID_FILE=$CSW_DATA_DIR/redis1.pid
REDIS_LOG_FILE=$CSW_DATA_DIR/redis1.log

case "$1" in
    start)

        # Start the Config Service
        if [ ! -d "$CSW_INSTALL" ] ; then
            echo "Please set CSW_INSTALL to the root directory where the csw software is installed"
            exit 1
        else
            # Start Config Service
            if [ "$START_CONFIG_SERVICE" == "yes" ] ; then
                if [ -f $CS_PID_FILE ] ; then
                    echo "Config Service pid file $CS_PID_FILE exists, process is already running or crashed?"
                else
                    $CSW_INSTALL/bin/cs $CS_OPTIONS > $CS_LOG_FILE 2>&1 &
                    echo $! > $CS_PID_FILE
                fi
            fi

            # Start Redis based services using trackLocation
            if [ -f $REDIS_PID_FILE ] ; then
                echo "Redis pid file $REDIS_PID_FILE exists, process is already running or crashed?"
            else
                $CSW_INSTALL/bin/tracklocation --name "$REDIS_SERVICES" --port $REDIS_PORT --command "$REDIS_SERVER --protected-mode no --port $REDIS_PORT" > $REDIS_LOG_FILE 2>&1 &
                echo $! > $REDIS_PID_FILE
				# Load the default alarms in to the Alarm Service Redis instance
				$CSW_INSTALL/bin/asconsole --init $CSW_INSTALL/conf/alarms.conf >> $REDIS_LOG_FILE 2>&1 &
			fi
        fi
        ;;
    stop)
        # Stop Redis
        if [ ! -f $REDIS_PID_FILE ]
        then
            echo "Redis $REDIS_PID_FILE does not exist, process is not running"
        else
            PID=$(cat $REDIS_PID_FILE)
            echo "Stopping Redis..."
            $REDIS_CLIENT -p $REDIS_PORT shutdown
            while [ -x /proc/${PID} ]
            do
                echo "Waiting for Redis to shutdown ..."
                sleep 1
            done
            echo "Redis stopped"
            rm -f $REG_PID_FILE $REG_LOG_FILE $REDIS_LOG_FILE $REDIS_PID_FILE
        fi
        # Stop Config Service
        if [ "$START_CONFIG_SERVICE" == "yes" ] ; then
            if [ ! -f $CS_PID_FILE ]; then
				echo "Config Service $CS_PID_FILE does not exist, process is not running"
            else
				PID=$(cat $CS_PID_FILE)
				echo "Stopping Config Service..."
				kill $PID
				rm -f $CS_PID_FILE $CS_LOG_FILE
            fi
        fi
        ;;
    *)
        echo "Please use start or stop as first argument"
        ;;
esac
