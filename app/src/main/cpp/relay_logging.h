#pragma once
#ifndef BUBBLE_RELAY_LOGGING
#define BUBBLE_RELAY_LOGGING 0
#endif
#if BUBBLE_RELAY_LOGGING
#include <android/log.h>
#define BUBBLE_RELAY_LOG(priority, ...) __android_log_print(priority, "BubbleRelayBP", __VA_ARGS__)
#else
// A macro, not a no-op function: disabled arguments are not evaluated.
#define BUBBLE_RELAY_LOG(...) ((void)0)
#endif
