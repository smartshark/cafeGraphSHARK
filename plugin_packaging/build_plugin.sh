#!/bin/bash

TOOL=cafeGraphSHARK

TARGET=tmp/${TOOL}_plugin
current=`pwd`
mkdir -p $TARGET
mkdir -p $TARGET/build/
mkdir -p $TARGET/${TOOL}/
cp -R ../src $TARGET/${TOOL}/
#cp -R ../lib $TARGET/${TOOL}/
cp -R ../gradle* $TARGET/${TOOL}/
cp ../build.gradle $TARGET/${TOOL}/
cp ../settings.gradle $TARGET/${TOOL}/

mkdir -p $TARGET/commonSHARK/
cp -R ../../commonSHARK/src $TARGET/commonSHARK/
cp -R ../../commonSHARK/lib $TARGET/commonSHARK/
cp ../../commonSHARK/build.gradle $TARGET/commonSHARK/

cp * $TARGET/
cd $TARGET

tar -cvf "$current/${TOOL}_plugin.tar" --exclude=*.tar --exclude=build_plugin.sh *
