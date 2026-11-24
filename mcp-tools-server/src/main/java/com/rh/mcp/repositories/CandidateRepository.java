package com.rh.mcp.repositories;

import com.rh.mcp.entities.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CandidateRepository extends JpaRepository<Candidate, Long> {

    List<Candidate> findBySkillsContainingIgnoreCase(String skills);

     List<Candidate> findByNameContainingIgnoreCase(String name);
}