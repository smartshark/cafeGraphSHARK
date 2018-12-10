#!/bin/sh
PLUGIN_PATH=$1

cd $PLUGIN_PATH/cafeGraphSHARK

# Build jar file or perform other tasks
./gradlew shadowJar

cp build/libs/cafeGraphSHARK*.jar ../build/cafeGraphSHARK.jar