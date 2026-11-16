package ru.geekideas.mc.climate;

import java.util.Locale;

/**
 * Season and the value of the cells.applied_state field. Order = order of the counter columns in
 * chunks.
 */
enum Season {
  SPRING("spring"),
  SUMMER("summer"),
  AUTUMN("autumn"),
  WINTER("winter"),
  RESTORE("original");

  final String state;

  Season(String state) {
    this.state = state;
  }

  static Season parse(String text) {
    return switch (text.toLowerCase(Locale.ROOT)) {
      case "spring" -> SPRING;
      case "summer" -> SUMMER;
      case "autumn" -> AUTUMN;
      case "winter" -> WINTER;
      case "restore" -> RESTORE;

      default -> null;
    };
  }
}
