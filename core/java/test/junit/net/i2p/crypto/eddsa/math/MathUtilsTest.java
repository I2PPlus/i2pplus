/**
 * EdDSA-Java by str4d.
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa.math;

import static org.hamcrest.MatcherAssert.assertThat;

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;

import org.hamcrest.core.IsEqual;
import org.junit.Test;

/**
 * Self-check of the MathUtils test helpers themselves: neutral-element
 * addition, representation conversions, and scalar multiply by zero.
 * Originally a @Test inside MathUtils, which the *Test scan never ran.
 */
public class MathUtilsTest {

    private static final EdDSANamedCurveSpec ed25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
    private static final Curve curve = ed25519.getCurve();

    @Test
    public void mathUtilsWorkAsExpected() {
        final GroupElement neutral = GroupElement.p3(curve, curve.getField().ZERO, curve.getField().ONE, curve.getField().ONE, curve.getField().ZERO);
        for (int i = 0; i < 1000; i++) {
            final GroupElement g = MathUtils.getRandomGroupElement();

            // Act:
            final GroupElement h1 = MathUtils.addGroupElements(g, neutral);
            final GroupElement h2 = MathUtils.addGroupElements(neutral, g);

            // Assert:
            assertThat(g, IsEqual.equalTo(h1));
            assertThat(g, IsEqual.equalTo(h2));
        }

        for (int i = 0; i < 1000; i++) {
            GroupElement g = MathUtils.getRandomGroupElement();

            // P3 -> P2.
            GroupElement h = MathUtils.toRepresentation(g, GroupElement.Representation.P2);
            assertThat(h, IsEqual.equalTo(g));
            // P3 -> P1P1.
            h = MathUtils.toRepresentation(g, GroupElement.Representation.P1P1);
            assertThat(g, IsEqual.equalTo(h));

            // P3 -> CACHED.
            h = MathUtils.toRepresentation(g, GroupElement.Representation.CACHED);
            assertThat(h, IsEqual.equalTo(g));

            // P3 -> P2 -> P3.
            g = MathUtils.toRepresentation(g, GroupElement.Representation.P2);
            h = MathUtils.toRepresentation(g, GroupElement.Representation.P3);
            assertThat(g, IsEqual.equalTo(h));

            // P3 -> P2 -> P1P1.
            g = MathUtils.toRepresentation(g, GroupElement.Representation.P2);
            h = MathUtils.toRepresentation(g, GroupElement.Representation.P1P1);
            assertThat(g, IsEqual.equalTo(h));
        }

        for (int i = 0; i < 10; i++) {
            // Arrange:
            final GroupElement g = MathUtils.getRandomGroupElement();

            // Act:
            final GroupElement h = MathUtils.scalarMultiplyGroupElement(g, curve.getField().ZERO);

            // Assert:
            assertThat(curve.getZero(GroupElement.Representation.P3), IsEqual.equalTo(h));
        }
    }
}
