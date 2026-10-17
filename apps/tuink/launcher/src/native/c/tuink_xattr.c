#include "tuink_xattr.h"

#include <errno.h>
#include <limits.h>
#include <sys/xattr.h>

int tuink_getxattr(const char *path, const char *name, void *value, size_t size) {
    ssize_t result = getxattr(path, name, value, size);
    if (result < 0) {
        return -errno;
    }
    return result > INT_MAX ? -EOVERFLOW : (int) result;
}

int tuink_setxattr(const char *path, const char *name, const void *value, size_t size) {
    int result = setxattr(path, name, value, size, 0);
    return result < 0 ? -errno : result;
}

int tuink_removexattr(const char *path, const char *name) {
    int result = removexattr(path, name);
    return result < 0 ? -errno : result;
}
