public class Slugify { public static String slugify(String s) { return s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""); } }
