package org.robolectric.shadows;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.Locale;

@Implements(value = libcore.icu.ICU.class, isInAndroidSdk = false)
public class ShadowICU {

  @Implementation
  public static String addLikelySubtags(String locale) {
    return "en-US";
  }

  @Implementation
  public static String getBestDateTimePattern(String skeleton, Locale locale) {
    return "h:mm a";
  }
}
