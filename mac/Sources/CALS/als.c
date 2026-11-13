// Ambient light sensor access. Ported from spikes/mac-light-sensor/alsprobe.c.
#include "als.h"
#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>

typedef struct __IOHIDEvent *IOHIDEventRef;
typedef struct __IOHIDServiceClient *IOHIDServiceClientRef;
typedef struct __IOHIDEventSystemClient *IOHIDEventSystemClientRef;
extern IOHIDEventSystemClientRef IOHIDEventSystemClientCreate(CFAllocatorRef);
extern void IOHIDEventSystemClientSetMatching(IOHIDEventSystemClientRef, CFDictionaryRef);
extern CFArrayRef IOHIDEventSystemClientCopyServices(IOHIDEventSystemClientRef);
extern IOHIDEventRef IOHIDServiceClientCopyEvent(IOHIDServiceClientRef, int64_t, int32_t, int64_t);
extern double IOHIDEventGetFloatValue(IOHIDEventRef, int32_t);

#define ALS_EVENT 12
#define ALS_FIELD (ALS_EVENT << 16)

static IOHIDEventSystemClientRef g_client;
static IOHIDServiceClientRef g_svc;
static io_connect_t g_lmu;

static int hid_open(void) {
    g_client = IOHIDEventSystemClientCreate(kCFAllocatorDefault);
    if (!g_client) return 0;
    int page = 0xFF00, usage = 4;
    CFNumberRef p = CFNumberCreate(NULL, kCFNumberIntType, &page);
    CFNumberRef u = CFNumberCreate(NULL, kCFNumberIntType, &usage);
    const void *k[] = {CFSTR("PrimaryUsagePage"), CFSTR("PrimaryUsage")};
    const void *v[] = {p, u};
    CFDictionaryRef m = CFDictionaryCreate(NULL, k, v, 2, &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    IOHIDEventSystemClientSetMatching(g_client, m);
    CFRelease(m); CFRelease(p); CFRelease(u);
    CFArrayRef svcs = IOHIDEventSystemClientCopyServices(g_client);
    if (!svcs) return 0;
    for (CFIndex i = 0; i < CFArrayGetCount(svcs) && !g_svc; i++) {
        IOHIDServiceClientRef s = (IOHIDServiceClientRef)CFArrayGetValueAtIndex(svcs, i);
        IOHIDEventRef e = IOHIDServiceClientCopyEvent(s, ALS_EVENT, 0, 0);
        if (e) { CFRelease(e); g_svc = (IOHIDServiceClientRef)CFRetain(s); }
    }
    CFRelease(svcs);
    return g_svc != NULL;
}

static int lmu_open(void) {
    io_service_t svc = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("AppleLMUController"));
    if (!svc) return 0;
    kern_return_t kr = IOServiceOpen(svc, mach_task_self(), 0, &g_lmu);
    IOObjectRelease(svc);
    return kr == KERN_SUCCESS;
}

int als_open(void) { return hid_open() || lmu_open(); }

double als_read(int *ok) {
    if (g_svc) {
        IOHIDEventRef e = IOHIDServiceClientCopyEvent(g_svc, ALS_EVENT, 0, 0);
        if (!e) { *ok = 0; return 0; }
        double v = IOHIDEventGetFloatValue(e, ALS_FIELD);
        CFRelease(e);
        *ok = 1;
        return v;
    }
    if (g_lmu) {
        uint64_t out[2] = {0};
        uint32_t cnt = 2;
        if (IOConnectCallMethod(g_lmu, 0, NULL, 0, NULL, 0, out, &cnt, NULL, NULL) != KERN_SUCCESS) { *ok = 0; return 0; }
        *ok = 1;
        return (double)(out[0] + out[1]) / 2.0;
    }
    *ok = 0;
    return 0;
}
