#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/input.h>

#define FN(name) Java_dev_keyrelay_helper_Evdev_##name

JNIEXPORT jint JNICALL FN(open)(JNIEnv *env, jclass cls, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (p == NULL) return -ENOMEM;
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, path, p);
    return fd >= 0 ? fd : -err;
}

JNIEXPORT void JNICALL FN(close)(JNIEnv *env, jclass cls, jint fd) { close(fd); }

JNIEXPORT jint JNICALL FN(grab)(JNIEnv *env, jclass cls, jint fd, jboolean on) {
    return ioctl(fd, EVIOCGRAB, on ? 1 : 0) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL FN(evBits)(JNIEnv *env, jclass cls, jint fd) {
    unsigned int bits = 0;
    return ioctl(fd, EVIOCGBIT(0, sizeof(bits)), &bits) < 0 ? 0 : (jint) bits;
}

JNIEXPORT jbyteArray JNICALL FN(keyBits)(JNIEnv *env, jclass cls, jint fd) {
    unsigned char bits[KEY_MAX / 8 + 1] = {0};
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(bits)), bits) < 0) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, sizeof(bits));
    (*env)->SetByteArrayRegion(env, out, 0, sizeof(bits), (const jbyte *) bits);
    return out;
}

static jstring string_ioctl(JNIEnv *env, int fd, unsigned long request) {
    char buf[256] = {0};
    if (ioctl(fd, request, buf) < 0) return NULL;
    buf[sizeof(buf) - 1] = 0;
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL FN(name)(JNIEnv *env, jclass cls, jint fd) { return string_ioctl(env, fd, EVIOCGNAME(255)); }
JNIEXPORT jstring JNICALL FN(phys)(JNIEnv *env, jclass cls, jint fd) { return string_ioctl(env, fd, EVIOCGPHYS(255)); }
JNIEXPORT jstring JNICALL FN(uniq)(JNIEnv *env, jclass cls, jint fd) { return string_ioctl(env, fd, EVIOCGUNIQ(255)); }

// Reads whole input_event records into a direct buffer: no JNI copy, no allocation per call.
JNIEXPORT jint JNICALL FN(read)(JNIEnv *env, jclass cls, jint fd, jobject buffer) {
    void *p = (*env)->GetDirectBufferAddress(env, buffer);
    jlong cap = (*env)->GetDirectBufferCapacity(env, buffer);
    if (p == NULL || cap < (jlong) sizeof(struct input_event)) return -EINVAL;
    ssize_t n = read(fd, p, (size_t) cap);
    return n >= 0 ? (jint) n : -errno;
}
