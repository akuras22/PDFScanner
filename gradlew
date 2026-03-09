#!/bin/sh

# Gradle start up script for POSIX

app_path=$0
while [ -h "$app_path" ]; do
    ls=$( ls -ld -- "$app_path" )
    link=${ls#*' -> '}
    case $link in
      /*) app_path=$link ;;
      *)  app_path=${app_path%"${app_path##*/}"}$link ;;
    esac
done

APP_BASE_NAME=${0##*/}
APP_HOME=$( cd -P "${app_path%"${app_path##*/}"}." > /dev/null && printf '%s\n' "$PWD" ) || exit

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ] ; then
    JAVACMD=$JAVA_HOME/bin/java
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    if ! command -v java >/dev/null 2>&1 ; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
        exit 1
    fi
fi

exec "$JAVACMD" \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
