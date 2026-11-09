package com.yizhaoqi.smartpai.repository;

import com.yizhaoqi.smartpai.model.AnswerFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnswerFeedbackRepository extends JpaRepository<AnswerFeedback, Long> {

    Page<AnswerFeedback> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<AnswerFeedback> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AnswerFeedback> findByStatusOrderByCreatedAtAsc(String status);

    long countByStatus(String status);
}
