/* Demo: Key-value packet parser with validation, lookup, and checksum calculation. */
#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_FIELDS 16
#define MAX_TOKEN 64

typedef struct {
    char key[MAX_TOKEN];
    char value[MAX_TOKEN];
} Field;

typedef struct {
    Field fields[MAX_FIELDS];
    size_t count;
} Packet;

static void packet_init(Packet *pkt) {
    pkt->count = 0;
    for (size_t i = 0; i < MAX_FIELDS; i++) {
        pkt->fields[i].key[0] = '\0';
        pkt->fields[i].value[0] = '\0';
    }
}

static int add_field(Packet *pkt, const char *key, const char *value) {
    if (pkt->count >= MAX_FIELDS) {
        return -1;
    }
    strncpy(pkt->fields[pkt->count].key, key, MAX_TOKEN - 1);
    strncpy(pkt->fields[pkt->count].value, value, MAX_TOKEN - 1);
    pkt->fields[pkt->count].key[MAX_TOKEN - 1] = '\0';
    pkt->fields[pkt->count].value[MAX_TOKEN - 1] = '\0';
    pkt->count++;
    return 0;
}

static void trim(char *s) {
    size_t len = strlen(s);
    size_t start = 0;
    while (start < len && isspace((unsigned char)s[start])) {
        start++;
    }
    if (start > 0) {
        memmove(s, s + start, len - start + 1);
        len = strlen(s);
    }
    while (len > 0 && isspace((unsigned char)s[len - 1])) {
        s[len - 1] = '\0';
        len--;
    }
}

static int parse_line(Packet *pkt, const char *line) {
    char local[256];
    strncpy(local, line, sizeof(local) - 1);
    local[sizeof(local) - 1] = '\0';

    char *sep = strchr(local, '=');
    if (!sep) {
        return -1;
    }

    *sep = '\0';
    char *key = local;
    char *value = sep + 1;
    trim(key);
    trim(value);

    if (key[0] == '\0' || value[0] == '\0') {
        return -2;
    }

    return add_field(pkt, key, value);
}

static const char *packet_get(const Packet *pkt, const char *key) {
    for (size_t i = 0; i < pkt->count; i++) {
        if (strcmp(pkt->fields[i].key, key) == 0) {
            return pkt->fields[i].value;
        }
    }
    return NULL;
}

static uint32_t checksum(const Packet *pkt) {
    uint32_t sum = 2166136261u;
    for (size_t i = 0; i < pkt->count; i++) {
        const char *k = pkt->fields[i].key;
        const char *v = pkt->fields[i].value;
        while (*k) {
            sum ^= (uint8_t)*k++;
            sum *= 16777619u;
        }
        while (*v) {
            sum ^= (uint8_t)*v++;
            sum *= 16777619u;
        }
    }
    return sum;
}

static void print_packet(const Packet *pkt) {
    printf("packet fields: %zu\n", pkt->count);
    for (size_t i = 0; i < pkt->count; i++) {
        printf("  %s = %s\n", pkt->fields[i].key, pkt->fields[i].value);
    }
    printf("checksum: 0x%08x\n", checksum(pkt));
}

int main(void) {
    const char *lines[] = {
        "type = telemetry",
        "node = sensor-17",
        "temp = 23.4",
        "humidity = 41",
        "state = ok"
    };

    Packet pkt;
    packet_init(&pkt);

    for (size_t i = 0; i < sizeof(lines) / sizeof(lines[0]); i++) {
        int rc = parse_line(&pkt, lines[i]);
        if (rc != 0) {
            fprintf(stderr, "parse error %d at line %zu\n", rc, i + 1);
            return 1;
        }
    }

    print_packet(&pkt);
    const char *node = packet_get(&pkt, "node");
    if (node) {
        printf("node id: %s\n", node);
    }

    return 0;
}
