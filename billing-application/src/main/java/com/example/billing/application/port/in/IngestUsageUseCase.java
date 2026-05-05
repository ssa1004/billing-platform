package com.example.billing.application.port.in;

import com.example.billing.application.command.IngestUsageCommand;

public interface IngestUsageUseCase {

    /**
     * @return true = 새로 저장됨, false = 이미 처리된 eventId (멱등성으로 무시)
     */
    boolean ingest(IngestUsageCommand cmd);
}
