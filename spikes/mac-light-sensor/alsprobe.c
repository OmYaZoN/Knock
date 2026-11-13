// alsprobe.c : TapDrop spike #1. Can a MacBook's ambient light sensor see a phone screen
// flash white/black fast enough to run a tap round (~0.5 s)?
//
// Build:  clang -O2 -Wall -o alsprobe alsprobe.c -framework IOKit -framework CoreFoundation
// Run:    ./alsprobe                 (polls at 200 Hz forever, Ctrl-C to stop)
//         ./alsprobe -r 500 -t 30 -c run1.csv
//
// Apple Silicon: reads the sensor through the private IOHIDEventSystemClient API
// (usage page 0xFF00, usage 4, event type 12). Intel: falls back to AppleLMUController.
// Private API: fine for a spike and for direct distribution, NOT allowed on the Mac App Store.
//
// What matters in the output is "updates/s": how many times per second the value actually
// changes. Polling at 200 Hz is pointless if the sensor only publishes 10 new values a second.

#include <CoreFoundation/CoreFoundation.h>
#include <IOKit/IOKitLib.h>
#include <mach/mach_time.h>
#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// ---- private IOHIDEventSystem API (IOKit.framework exports these) ----
typedef struct __IOHIDEvent *IOHIDEventRef;
typedef struct __IOHIDServiceClient *IOHIDServiceClientRef;
typedef struct __IOHIDEventSystemClient *IOHIDEventSystemClientRef;
extern IOHIDEventSystemClientRef IOHIDEventSystemClientCreate(CFAllocatorRef);
extern void IOHIDEventSystemClientSetMatching(IOHIDEventSystemClientRef, CFDictionaryRef);
extern CFArrayRef IOHIDEventSystemClientCopyServices(IOHIDEventSystemClientRef);
extern IOHIDEventRef IOHIDServiceClientCopyEvent(IOHIDServiceClientRef, int64_t type, int32_t options, int64_t ts);
extern double IOHIDEventGetFloatValue(IOHIDEventRef, int32_t field);
extern CFTypeRef IOHIDServiceClientCopyProperty(IOHIDServiceClientRef, CFStringRef);

#define kHIDEventTypeALS 12
#define kHIDFieldALSLevel (kHIDEventTypeALS << 16)

static volatile sig_atomic_t g_stop = 0;
static void on_sigint(int s) { (void)s; g_stop = 1; }

static double now_s(void) {
    static mach_timebase_info_data_t tb;
    if (!tb.denom) mach_timebase_info(&tb);
    return (double)mach_absolute_time() * tb.numer / tb.denom / 1e9;
}

// ---- backend: Apple Silicon ----
static IOHIDEventSystemClientRef g_client;
static IOHIDServiceClientRef g_svc;

static int hid_open(void) {
    g_client = IOHIDEventSystemClientCreate(kCFAllocatorDefault);
    if (!g_client) return 0;
    int page = 0xFF00, usage = 4;
    CFNumberRef p = CFNumberCreate(NULL, kCFNumberIntType, &page);
    CFNumberRef u = CFNumberCreate(NULL, kCFNumberIntType, &usage);
    const void *k[] = {CFSTR("PrimaryUsagePage"), CFSTR("PrimaryUsage")};
    const void *v[] = {p, u};
    CFDictionaryRef m = CFDictionaryCreate(NULL, k, v, 2, &kCFTypeDictionaryKeyCallBacks,
                                           &kCFTypeDictionaryValueCallBacks);
    IOHIDEventSystemClientSetMatching(g_client, m);
    CFRelease(m); CFRelease(p); CFRelease(u);

    CFArrayRef svcs = IOHIDEventSystemClientCopyServices(g_client);
    if (!svcs) return 0;
    CFIndex n = CFArrayGetCount(svcs);
    fprintf(stderr, "HID: %ld matching ALS service(s)\n", (long)n);
    for (CFIndex i = 0; i < n; i++) {
        IOHIDServiceClientRef s = (IOHIDServiceClientRef)CFArrayGetValueAtIndex(svcs, i);
        IOHIDEventRef e = IOHIDServiceClientCopyEvent(s, kHIDEventTypeALS, 0, 0);
        if (e) {
            CFRelease(e);
            g_svc = (IOHIDServiceClientRef)CFRetain(s);
            CFTypeRef prod = IOHIDServiceClientCopyProperty(s, CFSTR("Product"));
            if (prod && CFGetTypeID(prod) == CFStringGetTypeID()) {
                char buf[256];
                CFStringGetCString(prod, buf, sizeof buf, kCFStringEncodingUTF8);
                fprintf(stderr, "HID: using service #%ld (%s)\n", (long)i, buf);
            }
            if (prod) CFRelease(prod);
            break;
        }
    }
    CFRelease(svcs);
    return g_svc != NULL;
}

static int hid_read(double *lux) {
    IOHIDEventRef e = IOHIDServiceClientCopyEvent(g_svc, kHIDEventTypeALS, 0, 0);
    if (!e) return 0;
    *lux = IOHIDEventGetFloatValue(e, kHIDFieldALSLevel);
    CFRelease(e);
    return 1;
}

// ---- backend: Intel (AppleLMUController) ----
static io_connect_t g_lmu;

static int lmu_open(void) {
    io_service_t svc = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("AppleLMUController"));
    if (!svc) return 0;
    kern_return_t kr = IOServiceOpen(svc, mach_task_self(), 0, &g_lmu);
    IOObjectRelease(svc);
    if (kr != KERN_SUCCESS) return 0;
    fprintf(stderr, "LMU: using AppleLMUController (Intel)\n");
    return 1;
}

static int lmu_read(double *lux) {
    uint64_t out[2] = {0};
    uint32_t cnt = 2;
    if (IOConnectCallMethod(g_lmu, 0, NULL, 0, NULL, 0, out, &cnt, NULL, NULL) != KERN_SUCCESS) return 0;
    *lux = (double)(out[0] + out[1]) / 2.0;  // raw units, not lux
    return 1;
}

// ---- guided mode ----
typedef struct { const char *say; double secs; } Phase;
static const Phase PH[] = {
    {"WARMUP      don't touch anything", 5},
    {"ROOM        leave the notch uncovered", 6},
    {"FINGER      cover the notch with a fingertip", 6},
    {"WHITE-ON    press WHITE phone screen (full brightness) flat on the notch", 6},
    {"BLACK-ON    keep it pressed, turn the phone screen OFF (power button)", 6},
    {"WHITE-NEAR  screen on again, hold it ~3 cm AWAY from the notch", 6},
    {"WHITE-ON2   press it flat on the notch again", 6},
    {"BLACK-ON2   keep it pressed, screen OFF", 6},
};
#define NPH (int)(sizeof PH / sizeof PH[0])
#define SETTLE 1.5   /* seconds ignored at the start of each phase while you move */
#define MAXS 4096
static double g_buf[NPH][MAXS];
static int g_n[NPH];

static int cmpd(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return (x > y) - (x < y);
}
static double med(int i) {
    if (!g_n[i]) return NAN;
    qsort(g_buf[i], g_n[i], sizeof(double), cmpd);
    return g_buf[i][g_n[i] / 2];
}

// ---- main ----
static void bar(double v, double lo, double hi, char *out, int w) {
    double f = (hi > lo) ? (log1p(v) - log1p(lo)) / (log1p(hi) - log1p(lo)) : 0;
    if (f < 0) f = 0; if (f > 1) f = 1;
    int n = (int)(f * w + 0.5);
    for (int i = 0; i < w; i++) out[i] = i < n ? '#' : ' ';
    out[w] = 0;
}

int main(int argc, char **argv) {
    double rate = 200, dur = 0;
    const char *csv = NULL;
    int opt;
    int guided = 0;
    while ((opt = getopt(argc, argv, "r:t:c:gh")) != -1) {
        if (opt == 'r') rate = atof(optarg);
        else if (opt == 'g') guided = 1;
        else if (opt == 't') dur = atof(optarg);
        else if (opt == 'c') csv = optarg;
        else { fprintf(stderr, "usage: %s [-g] [-r hz] [-t seconds] [-c out.csv]  (-g = guided test)\n", argv[0]); return 2; }
    }

    int (*rd)(double *) = NULL;
    if (hid_open()) rd = hid_read;
    else if (lmu_open()) rd = lmu_read;
    else {
        fprintf(stderr, "FAIL: no ambient light sensor found via HID or AppleLMUController.\n"
                        "Report your Mac model (Apple menu > About This Mac) and macOS version.\n");
        return 1;
    }

    FILE *f = csv ? fopen(csv, "w") : NULL;
    if (f) fprintf(f, "t_s,value,changed,phase\n");
    double phase_end[NPH], acc = 0;
    for (int i = 0; i < NPH; i++) { acc += PH[i].secs; phase_end[i] = acc; }
    if (guided) dur = acc;
    int cur = -1;
    signal(SIGINT, on_sigint);

    const double period = 1.0 / rate, t0 = now_s();
    double last = NAN, next = t0, win_start = t0, last_change_t = t0;
    double lo = INFINITY, hi = -INFINITY, sess_lo = INFINITY, sess_hi = -INFINITY;
    long samples = 0, changes = 0, fails = 0, tot_changes = 0;
    double gap_max = 0, gap_sum = 0; long gap_n = 0;

    fprintf(stderr, "Polling at %.0f Hz. Cover the notch, press a white/black phone screen on it. Ctrl-C to stop.\n", rate);
    while (!g_stop) {
        double t = now_s();
        if (dur > 0 && t - t0 >= dur) break;
        int ph = -1;
        if (guided) {
            double el = t - t0;
            for (ph = 0; ph < NPH - 1 && el >= phase_end[ph]; ph++) {}
            if (ph != cur) {
                cur = ph;
                fprintf(stderr, "\a\n>>> %d/%d  %s  (%.0f s)\n", ph + 1, NPH, PH[ph].say, PH[ph].secs);
            }
        }
        double v;
        if (!rd(&v)) { fails++; }
        else {
            samples++;
            int changed = isnan(last) || v != last;
            if (changed) {
                if (!isnan(last)) {
                    double gap = t - last_change_t;
                    gap_sum += gap; gap_n++;
                    if (gap > gap_max) gap_max = gap;
                }
                last_change_t = t; changes++; tot_changes++; last = v;
                if (v < sess_lo) sess_lo = v;
                if (v > sess_hi) sess_hi = v;
                char b[41]; bar(v, sess_lo, sess_hi, b, 40);
                printf("%9.3f  %10.2f  |%s|\n", t - t0, v, b);
            }
            if (v < lo) lo = v;
            if (v > hi) hi = v;
            if (ph >= 0 && t - t0 >= phase_end[ph] - PH[ph].secs + SETTLE && g_n[ph] < MAXS)
                g_buf[ph][g_n[ph]++] = v;
            if (f) fprintf(f, "%.6f,%.4f,%d,%d\n", t - t0, v, changed, ph);
        }
        if (!guided && t - win_start >= 1.0) {
            fprintf(stderr, "-- %.0fs: %ld samples, %ld updates/s, min %.2f max %.2f, avg gap %.0f ms, worst gap %.0f ms, %ld read fails\n",
                    t - t0, samples, changes, lo, hi, gap_n ? 1000 * gap_sum / gap_n : 0, 1000 * gap_max, fails);
            fflush(stdout);
            win_start = t; samples = changes = fails = 0; lo = INFINITY; hi = -INFINITY;
            gap_sum = gap_max = 0; gap_n = 0;
        }
        next += period;
        double sl = next - now_s();
        if (sl > 0) usleep((useconds_t)(sl * 1e6));
        else next = now_s();
    }
    fprintf(stderr, "Done. %ld value changes in %.1f s (%.1f/s). Range seen: %.2f .. %.2f\n",
            tot_changes, now_s() - t0, tot_changes / (now_s() - t0), sess_lo, sess_hi);
    if (guided) {
        double m[NPH];
        printf("\n%-12s %10s %10s %10s\n", "phase", "median", "min", "max");
        for (int i = 0; i < NPH; i++) {
            m[i] = med(i);
            printf("%-12.*s %10.1f %10.1f %10.1f\n", 11, PH[i].say, m[i],
                   g_n[i] ? g_buf[i][0] : NAN, g_n[i] ? g_buf[i][g_n[i] - 1] : NAN);
        }
        printf("\nwhite-black pressed   : %.1f and %.1f\n", m[3] - m[4], m[6] - m[7]);
        printf("white near vs room    : %.1f vs %.1f\n", m[5], m[1]);
        printf("finger vs black-on    : %.1f vs %.1f\n", m[2], m[4]);
    }
    if (f) fclose(f);
    return 0;
}
