cmake_minimum_required(VERSION 3.10)
project(walkie C)
add_library(walkie SHARED radio.c)
target_link_libraries(walkie log)
