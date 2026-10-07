package com.wine.cellar.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.wine.cellar.entity.Member;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByPhone(String phone);

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    List<Member> findByLevel(String level);

    /** 行锁加载会员（出窖加积分 / 退窖扣积分，防止并发丢失更新） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findWithLockById(Long id);

    /** 只取积分（投影，不装入实体；退窖预检积分是否够扣，避免污染后续锁定读的一级缓存） */
    @Query("select m.points from Member m where m.id = :id")
    Optional<Integer> findPointsById(Long id);
}
