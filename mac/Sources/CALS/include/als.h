#ifndef KNOCK_ALS_H
#define KNOCK_ALS_H

/// Opens the ambient light sensor. Returns 1 on success, 0 if none was found.
int als_open(void);

/// Latest sensor value (lux on Apple Silicon, raw units on Intel). Sets *ok to 0 on a failed read.
double als_read(int *ok);

#endif
