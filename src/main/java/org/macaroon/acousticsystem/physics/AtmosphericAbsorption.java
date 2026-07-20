package org.macaroon.acousticsystem.physics;

/** ISO 9613-1 atmospheric absorption for a stationary, homogeneous atmosphere. */
public final class AtmosphericAbsorption {
    private static final double REFERENCE_TEMPERATURE_KELVIN = 293.15;
    private static final double TRIPLE_POINT_TEMPERATURE_KELVIN = 273.16;
    private static final double REFERENCE_PRESSURE_KPA = 101.325;
    private static final double DECIBELS_PER_NEPER = 8.685889638;

    private AtmosphericAbsorption() {
    }

    /** Returns pressure-amplitude attenuation in nepers per metre. */
    public static double amplitudeNepersPerMeter(
            double frequencyHertz,
            double temperatureCelsius,
            double relativeHumidityPercent,
            double pressureKilopascals
    ) {
        double temperature = temperatureCelsius + 273.15;
        double relativeHumidity = relativeHumidityPercent / 100.0;
        double saturationPressure = REFERENCE_PRESSURE_KPA * Math.pow(
                10.0,
                -6.8346 * Math.pow(
                        TRIPLE_POINT_TEMPERATURE_KELVIN / temperature,
                        1.261
                ) + 4.6151
        );
        double waterMolarConcentration = relativeHumidity
                * saturationPressure / pressureKilopascals;
        double pressureRatio = pressureKilopascals / REFERENCE_PRESSURE_KPA;
        double temperatureRatio = temperature / REFERENCE_TEMPERATURE_KELVIN;
        double oxygenRelaxation = pressureRatio * (
                24.0 + 4.04E4 * waterMolarConcentration
                        * (0.02 + waterMolarConcentration)
                        / (0.391 + waterMolarConcentration)
        );
        double nitrogenRelaxation = pressureRatio
                * Math.pow(temperatureRatio, -0.5)
                * (9.0 + 280.0 * waterMolarConcentration * Math.exp(
                        -4.17 * (Math.pow(temperatureRatio, -1.0 / 3.0) - 1.0)
                ));
        double squaredFrequency = frequencyHertz * frequencyHertz;
        double classicalLoss = 1.84E-11 / pressureRatio
                * Math.sqrt(temperatureRatio);
        double molecularLoss = Math.pow(temperatureRatio, -2.5) * (
                0.01275 * Math.exp(-2239.1 / temperature)
                        / (oxygenRelaxation
                        + squaredFrequency / oxygenRelaxation)
                        + 0.1068 * Math.exp(-3352.0 / temperature)
                        / (nitrogenRelaxation
                        + squaredFrequency / nitrogenRelaxation)
        );
        double decibelsPerMeter = DECIBELS_PER_NEPER
                * squaredFrequency * (classicalLoss + molecularLoss);
        return Math.max(0.0, decibelsPerMeter / DECIBELS_PER_NEPER);
    }

    public static float amplitudeGain(double nepersPerMeter, double distanceMeters) {
        return (float) Math.exp(
                -Math.max(0.0, nepersPerMeter) * Math.max(0.0, distanceMeters)
        );
    }
}
