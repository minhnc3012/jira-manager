package com.jiramanager.service;

import com.jiramanager.service.ConfluenceLinkParser.Kind;
import com.jiramanager.service.ConfluenceLinkParser.ParsedLink;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluenceLinkParserTest {

    @Test
    void parsesPageUrl() {
        ParsedLink link = ConfluenceLinkParser.parse(
                "https://example.atlassian.net/wiki/spaces/DOCS/pages/110264391/Demo+Page");
        assertThat(link.kind()).isEqualTo(Kind.PAGE);
        assertThat(link.spaceKey()).isEqualTo("DOCS");
        assertThat(link.pageId()).isEqualTo("110264391");
    }

    @Test
    void parsesFolderUrlAsUnsupported() {
        ParsedLink link = ConfluenceLinkParser.parse(
                "https://example.atlassian.net/wiki/spaces/DOCS/folder/110264391/Demo+Page");
        assertThat(link.kind()).isEqualTo(Kind.UNSUPPORTED_FOLDER);
        assertThat(link.spaceKey()).isEqualTo("DOCS");
        assertThat(link.pageId()).isEqualTo("110264391");
    }

    @Test
    void parsesSpaceHomeUrl() {
        ParsedLink link = ConfluenceLinkParser.parse("https://example.atlassian.net/wiki/spaces/DOCS");
        assertThat(link.kind()).isEqualTo(Kind.SPACE);
        assertThat(link.spaceKey()).isEqualTo("DOCS");
        assertThat(link.pageId()).isNull();
    }

    @Test
    void parsesSpaceOverviewUrl() {
        ParsedLink link = ConfluenceLinkParser.parse("https://example.atlassian.net/wiki/spaces/DOCS/overview");
        assertThat(link.kind()).isEqualTo(Kind.SPACE);
        assertThat(link.spaceKey()).isEqualTo("DOCS");
    }

    @Test
    void parsesSpaceUrlWithTrailingSlash() {
        ParsedLink link = ConfluenceLinkParser.parse("https://example.atlassian.net/wiki/spaces/DOCS/");
        assertThat(link.kind()).isEqualTo(Kind.SPACE);
        assertThat(link.spaceKey()).isEqualTo("DOCS");
    }

    @Test
    void blankOrNull_returnsInvalid() {
        assertThat(ConfluenceLinkParser.parse(null).kind()).isEqualTo(Kind.INVALID);
        assertThat(ConfluenceLinkParser.parse("   ").kind()).isEqualTo(Kind.INVALID);
    }

    @Test
    void unrecognizedUrl_returnsInvalid() {
        ParsedLink link = ConfluenceLinkParser.parse("https://example.com/not-confluence");
        assertThat(link.kind()).isEqualTo(Kind.INVALID);
    }
}
