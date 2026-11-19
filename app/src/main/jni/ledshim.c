// Our own open/ioctl/close shim. The vendor library (libjnielc.so) does exactly these three calls and
// nothing else for the LED bars (decoded by disassembly, see docs/protocol.md); this is the part of it
// we need, written from that description. Each function returns the result, or -errno on failure.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_app_panelled_LedDevice_nativeOpen(JNIEnv *env, jclass cls, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (path == NULL) return -ENOMEM;
    // Same flags as the vendor library: O_RDONLY | O_NOCTTY (0x100 on arm64 Linux).
    int fd = open(path, O_RDONLY | O_NOCTTY);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return fd < 0 ? -err : fd;
}

JNIEXPORT jint JNICALL
Java_app_panelled_LedDevice_nativeIoctl(JNIEnv *env, jclass cls, jint fd, jint request, jint arg) {
    int rc = ioctl(fd, request, arg);
    return rc < 0 ? -errno : rc;
}

JNIEXPORT jint JNICALL
Java_app_panelled_LedDevice_nativeClose(JNIEnv *env, jclass cls, jint fd) {
    return close(fd) < 0 ? -errno : 0;
}
