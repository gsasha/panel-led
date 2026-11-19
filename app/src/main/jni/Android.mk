LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE    := panelled
LOCAL_SRC_FILES := ledshim.c
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
