// Acceso al módulo analógico UHF del Inrico T298S.
// /dev/interphone_module: ioctl 0x23 = encender/apagar, 0x24 = PTT (sacado de libserial_port.so de fábrica).
// /dev/ttyMT0 a 9600 8N1: comandos AT+DMO...
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/select.h>

static int path_ioctl(const char *path, int req, int val) {
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;
    int r = ioctl(fd, req, &val);
    close(fd);
    return r;
}

static int dev_ioctl(int req, int val) { return path_ioctl("/dev/interphone_module", req, val); }

// LED del frontal: /dev/led_custom, ioctl 0x23 = rojo, 0x24 = verde (de libLedDriver.so de fábrica).
JNIEXPORT jint JNICALL Java_ovh_adan_walkie_Native_led(JNIEnv *e, jclass c, jint green, jint on) {
    return path_ioctl("/dev/led_custom", green ? 0x24 : 0x23, on ? 1 : 0);
}

JNIEXPORT jint JNICALL Java_ovh_adan_walkie_Native_power(JNIEnv *e, jclass c, jint on) {
    return dev_ioctl(0x23, on ? 1 : 0);
}

JNIEXPORT jint JNICALL Java_ovh_adan_walkie_Native_ptt(JNIEnv *e, jclass c, jint on) {
    return dev_ioctl(0x24, on ? 1 : 0);
}

JNIEXPORT jint JNICALL Java_ovh_adan_walkie_Native_open(JNIEnv *e, jclass c, jstring jpath) {
    const char *path = (*e)->GetStringUTFChars(e, jpath, 0);
    int fd = open(path, O_RDWR | O_NOCTTY);
    (*e)->ReleaseStringUTFChars(e, jpath, path);
    if (fd < 0) return -1;
    struct termios t;
    tcgetattr(fd, &t);
    cfmakeraw(&t);
    cfsetispeed(&t, B9600);
    cfsetospeed(&t, B9600);
    t.c_cflag |= CLOCAL | CREAD;
    tcsetattr(fd, TCSANOW, &t);
    tcflush(fd, TCIOFLUSH);
    return fd;
}

JNIEXPORT void JNICALL Java_ovh_adan_walkie_Native_close(JNIEnv *e, jclass c, jint fd) {
    if (fd >= 0) close(fd);
}

// Manda una línea y devuelve lo que conteste hasta quedarse callado "idleMs" (o "maxMs" en total).
JNIEXPORT jstring JNICALL Java_ovh_adan_walkie_Native_cmd(JNIEnv *e, jclass c, jint fd, jstring jline,
                                                          jint idleMs, jint maxMs) {
    tcflush(fd, TCIFLUSH);
    const char *line = (*e)->GetStringUTFChars(e, jline, 0);
    char out[256];
    int n = snprintf(out, sizeof out, "%s\r\n", line);
    (*e)->ReleaseStringUTFChars(e, jline, line);
    if (write(fd, out, n) != n) return 0;
    char buf[512];
    int len = 0, waited = 0;
    while (len < (int)sizeof buf - 1 && waited < maxMs) {
        fd_set f; FD_ZERO(&f); FD_SET(fd, &f);
        int wait = len ? idleMs : maxMs - waited;
        struct timeval tv = { wait / 1000, (wait % 1000) * 1000 };
        if (select(fd + 1, &f, 0, 0, &tv) <= 0) break;
        int r = read(fd, buf + len, sizeof buf - 1 - len);
        if (r <= 0) break;
        len += r;
        waited += 20;
    }
    buf[len] = 0;
    for (int i = 0; i < len; i++) if ((unsigned char)buf[i] > 126) buf[i] = 0x3f;
    return (*e)->NewStringUTF(e, buf);
}
