#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <stdlib.h>

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_NcmUsbNative_bulkRead(
        JNIEnv *env, jobject receiver, jint fd, jint endpoint, jbyteArray buffer, jint timeout) {
    (void) receiver;
    if (fd < 0 || endpoint < 0x81 || endpoint > 0x8f || buffer == NULL || timeout <= 0)
        return -EINVAL;
    jsize size = (*env)->GetArrayLength(env, buffer);
    if (size <= 0 || size > 65536) return -EINVAL;
    void *bytes = malloc((size_t) size);
    if (bytes == NULL) return -ENOMEM;
    struct usbdevfs_bulktransfer transfer = {
        .ep = (unsigned int) endpoint, .len = (unsigned int) size,
        .timeout = (unsigned int) timeout, .data = bytes,
    };
    int result = ioctl(fd, USBDEVFS_BULK, &transfer);
    int saved_errno = errno;
    if (result > 0 && result <= size)
        (*env)->SetByteArrayRegion(env, buffer, 0, result, (jbyte *) bytes);
    free(bytes);
    if (result < 0) return -saved_errno;
    return result <= size ? result : -EOVERFLOW;
}
