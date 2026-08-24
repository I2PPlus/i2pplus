package org.klomp.snark.web;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Map;

import org.klomp.snark.comments.Comment;
import org.klomp.snark.comments.CommentSet;
import org.junit.Test;

/**
 * Tests for I2PSnarkServlet displayComments helpers:
 * {@link I2PSnarkServlet#renderCommentsHeader},
 * {@link I2PSnarkServlet#renderCommentsList}.
 *
 * @since 0.9.71+
 */
public class DisplayCommentsValidatorTest {

    @Test
    public void testCommentsContextCreation() {
        I2PSnarkServlet.CommentsContext ctx = new I2PSnarkServlet.CommentsContext(
            null, true, true, true, "testuser", true);
        assertNull(ctx.snark);
        assertTrue(ctx.er);
        assertTrue(ctx.ec);
        assertTrue(ctx.esc);
        assertEquals("testuser", ctx.authorName);
        assertTrue(ctx.canRate);
    }

    @Test
    public void testCommentsContextImmutable() {
        I2PSnarkServlet.CommentsContext ctx = new I2PSnarkServlet.CommentsContext(
            null, false, false, false, "user", false);
        assertEquals("user", ctx.authorName);
        assertFalse(ctx.er);
        assertFalse(ctx.ec);
        assertFalse(ctx.esc);
        assertFalse(ctx.canRate);
    }

    @Test
    public void testCommentsHeaderResultCreation() {
        I2PSnarkServlet.CommentsHeaderResult hdr = new I2PSnarkServlet.CommentsHeaderResult(3);
        assertEquals(3, hdr.myRating);
    }

    @Test
    public void testCommentsHeaderResultZero() {
        I2PSnarkServlet.CommentsHeaderResult hdr = new I2PSnarkServlet.CommentsHeaderResult(0);
        assertEquals(0, hdr.myRating);
    }
}