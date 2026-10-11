cmake_minimum_required(VERSION 3.22.1)
project(keyrelay C)
add_library(keyrelay SHARED evdev.c)
