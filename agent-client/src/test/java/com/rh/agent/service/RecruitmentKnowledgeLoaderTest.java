package com.rh.agent.service;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecruitmentKnowledgeLoaderTest {

    @Test
    void loadsPdfDocumentsOnlyOnce() {
        VectorStore vectorStore = mock(VectorStore.class);
        RecruitmentKnowledgeLoader loader = new RecruitmentKnowledgeLoader(vectorStore);

        loader.loadIfNeeded();
        loader.loadIfNeeded();

        verify(vectorStore, times(1)).add(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void interpretsScoreUsingRetrievedScoreGuide() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("Candidate Score Guide\n90-100: Excellent\n80-89: Strong")));

        RecruitmentKnowledgeLoader loader = new RecruitmentKnowledgeLoader(vectorStore);

        String answer = loader.interpretScore(95, "technical screening");

        Assertions.assertTrue(answer.contains("95/100 is rated Excellent (90-100)"));
        Assertions.assertTrue(answer.contains("according to score-guide.pdf"));
        Assertions.assertFalse(answer.contains("demo"));
    }
}
