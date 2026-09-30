# Release shrinking (R8). Manifest components (activity, widget receiver) and resources named in
# layouts and the manifest are kept automatically.
#
# Nothing here uses reflection: JSON goes through org.json with explicit string keys, and enums that are
# stored or sent (PlanDriver, Screen, HaSettingsDriver/Strategy, VehicleField, ...) are written through
# an explicit wire/storedKey value or through name(), which is a string constant R8 leaves alone. No
# code calls Enum.valueOf or Class.forName on a stored string. So no keep rules are needed; the lines
# below only keep stack traces readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
