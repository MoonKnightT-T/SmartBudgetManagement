#!/bin/bash
cd "$(dirname "$0")"
mkdir -p out
javac -encoding UTF-8 -d out -sourcepath src src/Main.java || exit 1
echo "Starting Smart Budget - open http://localhost:8080 in your browser"
java -cp "out:lib/*" Main
