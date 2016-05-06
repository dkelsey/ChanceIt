#!/bin/bash

echo "compiling..."
javac com/chanceit/*.java

echo "runing..."
sleep 1

java com/chanceit/ChanceItServer2
