using System;
using System.Collections.Generic;
using System.Linq;

namespace Tokalot.Desktop.Core;

public sealed record Snippet(string Trigger, string Text);

/** Where speech becomes text. Cloud options use the OpenAI-style /audio/transcriptions API. */
public sealed record SttOption(string Id, string Label, string? Service, string BaseUrl, string Model, string Note);

/** Which LLM rewrites the raw transcript. */
public sealed record CleanupOption(string Id, string Label, string? Service, string BaseUrl, string Model, string Note);

public sealed record StyleOption(string Id, string Label, string Example, string Rule);

public sealed record AppCategory(string Id, string Label, string Blurb, string DefaultStyle, string Rule);

/** Same providers, styles and wording as the Android app, so both clean up text identically. */
public static class Catalog
{
    public static readonly SttOption[] Stt =
    {
        new("LOCAL", "On-device", null, "", "", "Free, offline, audio never leaves this PC. Less accurate."),
        new("GROQ", "Groq Whisper", "groq", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "Fastest and most accurate per dollar. Free tier covers heavy use."),
        new("OPENAI", "OpenAI", "openai", "https://api.openai.com/v1", "gpt-4o-transcribe", "Top accuracy, about $0.006 per minute."),
    };

    public static readonly CleanupOption[] Cleanup =
    {
        new("OFF", "Off", null, "", "", "Only strips um/uh. No AI, no cost."),
        new("GROQ", "Groq GPT-OSS 20B", "groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-20b", "Lowest latency, pennies a month."),
        new("CLAUDE", "Claude Haiku 4.5", "anthropic", "https://api.anthropic.com/v1", "claude-haiku-4-5", "Very good cleanup, about $0.60 a month at heavy use."),
        new("GEMINI", "Gemini Flash-Lite", "gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.1-flash-lite-preview", "Cheap and fast."),
        new("OPENAI", "OpenAI GPT-4.1 nano", "openai", "https://api.openai.com/v1", "gpt-4.1-nano", "Cheap and fast."),
    };

    public static readonly StyleOption[] Styles =
    {
        new("FORMAL", "Formal", "Hi Sam, I'll be there around 3 on Tuesday. Let me know if that works.",
            "Proper capitalization and punctuation, complete sentences, polished grammar. Keep the speaker's wording and tone; do not make it stiff."),
        new("CASUAL", "Casual", "Hey, I'll be there around 3 on Tuesday. Let me know if that works",
            "Normal capitalization and punctuation, relaxed conversational tone. A trailing period on the last sentence is optional."),
        new("VERY_CASUAL", "Very casual", "hey, ill be there around 3 on tuesday. that work for you?",
            "All lowercase, like a text message. Keep the punctuation that carries meaning: commas, question marks, and periods between sentences, so the message is never confusing. Only drop the period at the very end. Apostrophes are optional."),
    };

    public static readonly AppCategory[] Categories =
    {
        new("MESSAGING", "Messages", "WhatsApp, Slack, Discord, Teams, Messenger…", "CASUAL",
            "This is a chat or text message. Keep it conversational and brief. No greeting or sign-off unless the user said one."),
        new("EMAIL", "Email", "Outlook, Gmail, Thunderbird…", "FORMAL",
            "This is an email body. If the user dictates a greeting (\"Hi Sam\") put it on its own line followed by a blank line; put a dictated sign-off (\"Thanks, Alex\") on its own lines at the end. Use a blank line between paragraphs. Never invent a greeting, subject line or signature."),
        new("AI_CODE", "AI & code", "Claude, ChatGPT, VS Code, terminals…", "CASUAL",
            "This is a prompt for an AI assistant or a coding tool. Keep technical terms exact: programming languages, libraries, frameworks, acronyms (API, JSON, APK, SQL), version numbers. Write file names, CLI commands, function and variable names in their conventional form (build.gradle.kts, camelCase, snake_case) and wrap them in `backticks`. When the user lists steps or requirements, format them as a numbered or bulleted list."),
        new("OTHER", "Everything else", "Browsers, documents, notes, forms…", "CASUAL", ""),
    };

    public static readonly (string Id, string Name, string KeyPage)[] Services =
    {
        ("groq", "Groq", "console.groq.com/keys"),
        ("anthropic", "Anthropic (Claude)", "console.anthropic.com/settings/keys"),
        ("openai", "OpenAI", "platform.openai.com/api-keys"),
        ("gemini", "Google Gemini", "aistudio.google.com/apikey"),
    };

    /** Colors for the recording pill's bars while listening. Amber matches the app icon. */
    public static readonly (string Name, uint Argb)[] Accents =
    {
        ("Amber", 0xFFF2A93B), ("Green", 0xFF34C77B), ("Teal", 0xFF2EC4C4), ("Blue", 0xFF4C8DFF),
        ("Purple", 0xFFA77BFF), ("Pink", 0xFFFF6FAE), ("Red", 0xFFFF5A52), ("White", 0xFFFFFFFF),
    };

    public const string DefaultInstructions = "Always write \"lol\" in lowercase.";

    public static SttOption SttById(string id) => Stt.FirstOrDefault(s => s.Id == id) ?? Stt[1];
    public static CleanupOption CleanupById(string id) => Cleanup.FirstOrDefault(c => c.Id == id) ?? Cleanup[1];
    public static StyleOption StyleById(string id) => Styles.FirstOrDefault(s => s.Id == id) ?? Styles[1];
    public static AppCategory CategoryById(string id) => Categories.FirstOrDefault(c => c.Id == id) ?? Categories[3];
}
