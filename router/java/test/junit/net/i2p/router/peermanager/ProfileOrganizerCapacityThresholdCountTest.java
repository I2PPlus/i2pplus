package net.i2p.router.peermanager;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/**
 *  Pins how many capacities count as strictly above the mean, the count that
 *  decides whether the capacity threshold becomes the mean itself or a lower
 *  order statistic.
 *
 *  <p>The comparison is strict, and that matters whenever capacities cluster:
 *  the mean is then a repeated value, and any index derived from
 *  {@code Arrays.binarySearch} lands inside the run of entries that merely
 *  equal it, overcounting them as "exceeding". These are the values that
 *  overcounting would change.
 *
 *  @see ProfileOrganizerSpeedRankDecisionTest
 *  @since 0.9.71+
 */
public class ProfileOrganizerCapacityThresholdCountTest {

    /** A repeated mean: only the single entry above it counts. */
    @Test
    public void repeatedMeanCountsOnlyStrictlyGreater() {
        assertEquals(1, ProfileOrganizer.countExceedingMean(sorted(1, 2, 2, 2, 3), 2.0));
    }

    /** Every capacity equal to the mean: nothing exceeds it. */
    @Test
    public void identicalCapacitiesCountNothing() {
        assertEquals(0, ProfileOrganizer.countExceedingMean(sorted(2, 2, 2, 2, 2), 2.0));
    }

    /** A mean that occurs once is counted the same way either formulation. */
    @Test
    public void singleMeanIsCountedOnce() {
        assertEquals(1, ProfileOrganizer.countExceedingMean(sorted(1, 3, 5), 3.0));
        assertEquals(2, ProfileOrganizer.countExceedingMean(sorted(1, 3, 5, 9), 3.0));
    }

    /** A mean between entries, absent from the array. */
    @Test
    public void absentMeanCountsEverythingAboveIt() {
        assertEquals(1, ProfileOrganizer.countExceedingMean(sorted(1, 2, 4), 7.0 / 3));
        assertEquals(0, ProfileOrganizer.countExceedingMean(sorted(1, 2, 4), 8.0));
    }

    /** Duplicates below the mean must not be counted, whatever their position. */
    @Test
    public void duplicateRunsAroundTheMeanCountOnce() {
        assertEquals(1, ProfileOrganizer.countExceedingMean(sorted(1, 1, 1, 3), 1.5));
        assertEquals(1, ProfileOrganizer.countExceedingMean(sorted(2, 2, 2, 3), 2.25));
        assertEquals(3, ProfileOrganizer.countExceedingMean(sorted(1, 2, 2, 4, 4, 5), 3.0));
    }

    /** Degenerate inputs. */
    @Test
    public void emptyAndSingleCapacityInputs() {
        assertEquals(0, ProfileOrganizer.countExceedingMean(new double[0], 1.0));
        assertEquals(0, ProfileOrganizer.countExceedingMean(sorted(1), 1.0));
        assertEquals(1, ProfileOrganizer.countExceedingMean(sorted(1, 2), 1.5));
    }

    /**
     *  Sweep against the strict-greater count, over arrays drawn from a
     *  four-value alphabet so the mean repeats constantly and the comparison is
     *  not quietly the trivial "no duplicates" case.
     */
    @Test
    public void matchesStrictGreaterCountOnDuplicateHeavyArrays() {
        Random rnd = new Random(20261006L);
        double[] alphabet = {1.0, 2.0, 3.0, 4.0};
        for (int round = 0; round < 500; round++) {
            int length = 1 + rnd.nextInt(50);
            double[] capacities = new double[length];
            double total = 0.0;
            for (int i = 0; i < length; i++) {
                capacities[i] = alphabet[rnd.nextInt(alphabet.length)];
                total += capacities[i];
            }
            Arrays.sort(capacities);
            double mean = total / length;
            int expected = 0;
            for (double capacity : capacities) {
                if (capacity > mean) expected++;
            }
            assertEquals("round=" + round + " capacities=" + Arrays.toString(capacities)
                         + " mean=" + mean,
                         expected, ProfileOrganizer.countExceedingMean(capacities, mean));
        }
    }

    private static double[] sorted(double... values) {
        double[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }
}
