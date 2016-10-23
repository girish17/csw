package csw.examples.vsliceJava.assembly;

import com.typesafe.config.Config;
import csw.services.loc.ComponentType;
import csw.util.config.*;
import csw.util.config.Configurations.SetupConfig;
import csw.util.config.Configurations.ConfigKey;

import static javacsw.util.config.JItems.*;
import static javacsw.util.config.JConfigDSL.*;
import static javacsw.util.config.JUnitsOfMeasure.*;

import csw.services.pkg.Component.AssemblyInfo;

/**
 * TMT Source Code: 10/4/16.
 */
@SuppressWarnings("unused")
public class AssemblyContext {

  public final AssemblyInfo info;
  public final TromboneCalculationConfig calculationConfig;
  public final TromboneControlConfig controlConfig;

  // Assembly Info
  // These first three are set from the config file
  public final String componentName;
  public final String componentClassName;
  public final String componentPrefix;
  public final ComponentType componentType;
  public final String fullName;

  // Public command configurations
  // Init submit command
  public final String initPrefix;
  public final ConfigKey initCK;

  // Dataum submit command
  public final String datumPrefix;
  public final ConfigKey datumCK;

  // Stop submit command
  public final String stopPrefix;
  public final ConfigKey stopCK;

  // Move submit command
  public final String movePrefix;
  public final ConfigKey moveCK;

  public SetupConfig moveSC(double position) {
    return sc(moveCK.prefix(), jset(stagePositionKey, position).withUnits(stagePositionUnits));
  }

  // Position submit command
  public final String positionPrefix;
  public final ConfigKey positionCK;

  public SetupConfig positionSC(double rangeDistance) {
    return sc(positionCK.prefix(), jset(naRangeDistanceKey, rangeDistance).withUnits(naRangeDistanceUnits));
  }

  // setElevation submit command
  public final String setElevationPrefix;
  public final ConfigKey setElevationCK;

  public SetupConfig setElevationSC(double elevation) {
    return sc(setElevationCK.prefix(), jset(naElevationKey, elevation).withUnits(naElevationUnits));
  }

  // setAngle submit command
  public final String setAnglePrefx;
  public final ConfigKey setAngleCK;

  public SetupConfig setAngleSC(double zenithAngle) {
    return jadd(sc(setAngleCK.prefix()), za(zenithAngle));
  }

  // Follow submit command
  public final String followPrefix;
  public final ConfigKey followCK;
  public final BooleanKey nssInUseKey;

  public BooleanItem setNssInUse(boolean value) {
    return jset(nssInUseKey, value);
  }

  public SetupConfig followSC(boolean nssInUse) {
    return sc(followCK.prefix(), jset(nssInUseKey, nssInUse));
  }

  // A list of all commands
  public final ConfigKey[] allCommandKeys;

  // Shared key values --
  // Used by setElevation, setAngle
  public final StringKey configurationNameKey;
  public final StringKey configurationVersionKey;

  public final DoubleKey focusErrorKey;
  public final UnitsOfMeasure.Units focusErrorUnits;

  public DoubleItem fe(double error) {
    return jset(focusErrorKey, error).withUnits(focusErrorUnits);
  }

  public final DoubleKey zenithAngleKey;
  public final UnitsOfMeasure.Units zenithAngleUnits;

  public final DoubleItem za(double angle) {
    return jset(zenithAngleKey, angle).withUnits(zenithAngleUnits);
  }

  public final DoubleKey naRangeDistanceKey;
  public final UnitsOfMeasure.Units naRangeDistanceUnits;

  public DoubleItem rd(double rangedistance) {
    return jset(naRangeDistanceKey, rangedistance).withUnits(naRangeDistanceUnits);
  }

  public final DoubleKey naElevationKey;
  public final UnitsOfMeasure.Units naElevationUnits;

  public final DoubleItem el(double elevation) {
    return jset(naElevationKey, elevation).withUnits(naElevationUnits);
  }

  public final DoubleKey initialElevationKey;
  public final UnitsOfMeasure.Units initialElevationUnits;

  public DoubleItem iel(double elevation) {
    return jset(initialElevationKey, elevation).withUnits(initialElevationUnits);
  }

  public final DoubleKey stagePositionKey;
  public final UnitsOfMeasure.Units stagePositionUnits;

  public DoubleItem spos(double pos) {
    return jset(stagePositionKey, pos).withUnits(stagePositionUnits);
  }

  // ---------- Keys used by TromboneEventSubscriber and Others
  // This is the zenith angle from TCS
  public final String zenithAnglePrefix;
  public final ConfigKey zaConfigKey;

  // This is the focus error from RTC
  public final String focusErrorPrefix;
  public final ConfigKey feConfigKey;

  // ----------- Keys, etc. used by trombonePublisher, calculator, comamnds
  public final String aoSystemEventPrefix;
  public final String engStatusEventPrefix;
  public final String tromboneStateStatusEventPrefix;
  public final String axisStateEventPrefix;
  public final String axisStatsEventPrefix;

  // ---

  public AssemblyContext(AssemblyInfo info, TromboneCalculationConfig calculationConfig, TromboneControlConfig controlConfig) {
    this.info = info;
    this.calculationConfig = calculationConfig;
    this.controlConfig = controlConfig;

    componentName = info.componentName();
    componentClassName = info.componentClassName();
    componentPrefix = info.prefix();
    componentType = info.componentType();
    fullName = componentPrefix + "." + componentName;

    // Public command configurations
    // Init submit command
    initPrefix = componentPrefix + ".init";
    initCK = new ConfigKey(initPrefix);

    // Dataum submit command
    datumPrefix = componentPrefix + ".datum";
    datumCK = new ConfigKey(datumPrefix);

    // Stop submit command
    stopPrefix = componentPrefix + ".stop";
    stopCK = new ConfigKey(stopPrefix);

    // Move submit command
    movePrefix = componentPrefix + ".move";
    moveCK = new ConfigKey(movePrefix);

    // Position submit command
    positionPrefix = componentPrefix + ".position";
    positionCK = new ConfigKey(positionPrefix);

    // setElevation submit command
    setElevationPrefix = componentPrefix + ".setElevation";
    setElevationCK = new ConfigKey(setElevationPrefix);

    // setAngle submit command
    setAnglePrefx = componentPrefix + ".setAngle";
    setAngleCK = new ConfigKey(setAnglePrefx);

    // Follow submit command
    followPrefix = componentPrefix + ".follow";
    followCK = new ConfigKey(followPrefix);
    nssInUseKey = BooleanKey("nssInUse");

    // A list of all commands
    allCommandKeys = new ConfigKey[]{initCK, datumCK, stopCK, moveCK, positionCK, setElevationCK, setAngleCK, followCK};

    // Shared key values --
    // Used by setElevation, setAngle
    configurationNameKey = StringKey("initConfigurationName");
    configurationVersionKey = StringKey("initConfigurationVersion");

    focusErrorKey = DoubleKey("focus");
    focusErrorUnits = micrometers;

    zenithAngleKey = DoubleKey("zenithAngle");
    zenithAngleUnits = degrees;

    naRangeDistanceKey = DoubleKey("rangeDistance");
    naRangeDistanceUnits = kilometers;

    naElevationKey = DoubleKey("elevation");
    naElevationUnits = kilometers;

    initialElevationKey = DoubleKey("initialElevation");
    initialElevationUnits = kilometers;

    stagePositionKey = DoubleKey("stagePosition");
    stagePositionUnits = millimeters;

    // ---------- Keys used by TromboneEventSubscriber and Others
    // This is the zenith angle from TCS
    zenithAnglePrefix = "TCS.tcsPk.zenithAngle";
    zaConfigKey = new ConfigKey(zenithAnglePrefix);

    // This is the focus error from RTC
    focusErrorPrefix = "RTC.focusError";
    feConfigKey = new ConfigKey(focusErrorPrefix);

    // ----------- Keys, etc. used by trombonePublisher, calculator, comamnds
    aoSystemEventPrefix = componentPrefix + ".sodiumLayer";
    engStatusEventPrefix = componentPrefix + ".engr";
    tromboneStateStatusEventPrefix = componentPrefix + ".state";
    axisStateEventPrefix = componentPrefix + ".axis1State";
    axisStatsEventPrefix = componentPrefix + ".axis1Stats";
  }


  // --- static defs ---

  /**
   * Configuration class
   */
  public static class TromboneControlConfig {
    public final double positionScale;
    public final int minStageEncoder;
    public final double stageZero;
    public final int minEncoderLimit;
    public final int maxEncoderLimit;

    /**
     * Configuration class
     *
     * @param positionScale   value used to scale
     * @param stageZero       zero point in stage conversion
     * @param minStageEncoder minimum
     * @param minEncoderLimit minimum
     */
    public TromboneControlConfig(double positionScale, int minStageEncoder, double stageZero, int minEncoderLimit, int maxEncoderLimit) {
      this.positionScale = positionScale;
      this.minStageEncoder = minStageEncoder;
      this.stageZero = stageZero;
      this.minEncoderLimit = minEncoderLimit;
      this.maxEncoderLimit = maxEncoderLimit;
    }

    /**
     * Init from the given config
     */
    public TromboneControlConfig(Config config) {
      // Main prefix for keys used below
      String prefix = "csw.examples.trombone.assembly";

      this.positionScale = config.getDouble(prefix + ".control-config.positionScale");
      this.stageZero = config.getDouble(prefix + ".control-config.stageZero");
      this.minStageEncoder = config.getInt(prefix + ".control-config.minStageEncoder");
      this.minEncoderLimit = config.getInt(prefix + ".control-config.minEncoderLimit");
      this.maxEncoderLimit = config.getInt(prefix + ".control-config.maxEncoderLimit");
    }

  }

  /**
   * Configuration class
   */
  @SuppressWarnings("unused")
  public static class TromboneCalculationConfig {
    public final double defaultInitialElevation;
    public final double focusErrorGain;
    public final double upperFocusLimit;
    public final double lowerFocusLimit;
    public final double zenithFactor;

    /**
     * Configuration class
     *
     * @param defaultInitialElevation a default initial eleveation (possibly remove once workign)
     * @param focusErrorGain          gain value for focus error
     * @param upperFocusLimit         check for maximum focus error
     * @param lowerFocusLimit         check for minimum focus error
     * @param zenithFactor            an algorithm value for scaling zenith angle term
     */
    public TromboneCalculationConfig(double defaultInitialElevation, double focusErrorGain, double upperFocusLimit, double lowerFocusLimit, double zenithFactor) {
      this.defaultInitialElevation = defaultInitialElevation;
      this.focusErrorGain = focusErrorGain;
      this.upperFocusLimit = upperFocusLimit;
      this.lowerFocusLimit = lowerFocusLimit;
      this.zenithFactor = zenithFactor;
    }

    /**
     * Init from the given config
     */
    public TromboneCalculationConfig(Config config) {
      // Main prefix for keys used below
      String prefix = "csw.examples.trombone.assembly";

      this.defaultInitialElevation = config.getDouble(prefix + ".calculation-config.defaultInitialElevation");
      this.focusErrorGain = config.getDouble(prefix + ".calculation-config.focusErrorGain");
      this.upperFocusLimit = config.getDouble(prefix + ".calculation-config.upperFocusLimit");
      this.lowerFocusLimit = config.getDouble(prefix + ".calculation-config.lowerFocusLimit");
      this.zenithFactor = config.getDouble(prefix + ".calculation-config.zenithFactor");
    }
  }
}
