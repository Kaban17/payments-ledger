package com.paymentsledger.accounts;

import java.util.List;

/** A page of entries, newest first; {@code nextCursor} is null when there are no more. */
public record EntryPage(List<Entry> entries, String nextCursor) {}
