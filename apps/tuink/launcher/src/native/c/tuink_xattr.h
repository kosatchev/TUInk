#ifndef TUINK_XATTR_H
#define TUINK_XATTR_H

#include <stddef.h>

/* Each call returns a nonnegative result or -errno, captured before returning to Java. */
int tuink_getxattr(const char *path, const char *name, void *value, size_t size);
int tuink_setxattr(const char *path, const char *name, const void *value, size_t size);
int tuink_removexattr(const char *path, const char *name);

#endif
