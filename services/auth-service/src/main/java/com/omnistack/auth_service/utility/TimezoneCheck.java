package com.omnistack.auth_service.utility;

import java.util.TimeZone;

public class TimezoneCheck {
    public static void main(String[] args) {
        TimeZone tz = TimeZone.getDefault();
        System.out.println("JVM default timezone: " + tz.getID());
    }
}
