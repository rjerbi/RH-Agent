package com.rh.mcp.tools;

import com.rh.mcp.entities.Candidate;
import com.rh.mcp.repositories.CandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CandidateToolsTest {

    @Test
    void getCandidatesByStatusReturnsCandidatesWhoseCsvStatusMatches() {
        CandidateRepository repository = mock(CandidateRepository.class);
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(repository.findAll()).thenReturn(List.of(
                candidate(2L, "Sara Trabelsi"),
                candidate(4L, "Mariam Khaled"),
                candidate(5L, "Amine Salah")));

        CandidateTools tools = new CandidateTools(repository, mailSender);

        List<Candidate> result = tools.getCandidatesByStatus("interview pending");

        assertEquals(List.of("Sara Trabelsi", "Mariam Khaled", "Amine Salah"),
                result.stream().map(Candidate::getName).toList());
    }

    private Candidate candidate(Long id, String name) {
        Candidate candidate = new Candidate();
        candidate.setId(id);
        candidate.setName(name);
        return candidate;
    }
}
