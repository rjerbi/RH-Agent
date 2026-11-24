package com.rh.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

@Component
public class RecruitmentKnowledgeLoader {

    private static final Pattern SCORE_BAND =
            Pattern.compile("(\\d{1,3})\\s*-\\s*(\\d{1,3})\\s*:\\s*([A-Za-z][A-Za-z ]*)");

    private final VectorStore vectorStore;
    private boolean loaded;

    public RecruitmentKnowledgeLoader(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public synchronized void loadIfNeeded() {
        if (loaded) {
            return;
        }

        List<Document> documents = new ArrayList<>();
        documents.addAll(readPdf("interview-process.pdf"));
        documents.addAll(readPdf("score-guide.pdf"));
        documents.addAll(readPdf("evaluation-criteria.pdf"));

        if (documents.isEmpty()) {
            throw new IllegalStateException("No HR knowledge documents were loaded.");
        }

        vectorStore.add(documents);
        loaded = true;
    }

    public String interpretScore(int score, String scoreType) {
        if (score < 0 || score > 100) {
            return "DeepIT's score guide accepts scores from 0 to 100.";
        }

        loadIfNeeded();
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("screening score guide " + scoreType + " score bands")
                        .topK(3)
                        .build());

        for (Document document : documents) {
            String text = document.getText();
            if (text == null) {
                continue;
            }
            Matcher band = SCORE_BAND.matcher(text);
            while (band.find()) {
                int minimum = Integer.parseInt(band.group(1));
                int maximum = Integer.parseInt(band.group(2));
                if (score >= minimum && score <= maximum) {
                    return "A " + scoreType + " score of " + score + "/100 is rated "
                            + band.group(3).trim() + " (" + minimum + "-" + maximum
                            + ") according to score-guide.pdf. The guide says scores "
                            + "should not be used alone to make a recruitment decision. "
                            + "Use it together with the candidate's full profile.";
                }
            }
        }

        throw new IllegalStateException(
                "The retrieved HR documents do not contain a score band for " + score + ".");
    }

    private List<Document> readPdf(String fileName) {
        String path = "classpath:/knowledge/" + fileName;
        return new PagePdfDocumentReader(path).read();
    }
}
