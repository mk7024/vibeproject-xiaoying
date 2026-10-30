package com.muse.api;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

import static com.muse.api.ApiModels.*;
import static com.muse.api.Rows.*;

@Mapper
public interface StoreMapper {
    @Insert("INSERT INTO identities(id, display_name, created_at) VALUES(#{id}, #{displayName}, #{createdAt})")
    void insertIdentity(IdentityRow identity);

    @Insert("INSERT INTO sessions(token_hash, owner_id, expires_at) VALUES(#{hash}, #{ownerId}, #{expiresAt})")
    void insertSession(@Param("hash") String hash, @Param("ownerId") String ownerId,
                       @Param("expiresAt") Instant expiresAt);

    @Select("""
            SELECT i.* FROM identities i JOIN sessions s ON i.id = s.owner_id
            WHERE s.token_hash = #{hash} AND s.expires_at > #{now}
            """)
    IdentityRow findSession(@Param("hash") String hash, @Param("now") Instant now);

    @Select("SELECT * FROM identities WHERE id = #{id}")
    IdentityRow findIdentity(@Param("id") String id);

    @Delete("DELETE FROM sessions WHERE token_hash = #{hash}")
    void deleteSession(@Param("hash") String hash);

    @Delete("DELETE FROM sessions WHERE owner_id = #{ownerId}")
    void deleteSessionsByOwner(@Param("ownerId") String ownerId);

    @Insert("""
            INSERT INTO accounts(identity_id, email, password_hash, created_at, updated_at)
            VALUES(#{identityId}, #{email}, #{passwordHash}, #{createdAt}, #{updatedAt})
            """)
    void insertAccount(AccountRow account);

    @Select("SELECT * FROM accounts WHERE email = #{email}")
    AccountRow findAccountByEmail(@Param("email") String email);

    @Select("SELECT * FROM accounts WHERE identity_id = #{identityId}")
    AccountRow findAccountByIdentity(@Param("identityId") String identityId);

    @Update("""
            UPDATE accounts SET password_hash = #{passwordHash}, updated_at = #{updatedAt}
            WHERE identity_id = #{identityId}
            """)
    void updatePassword(@Param("identityId") String identityId, @Param("passwordHash") String passwordHash,
                        @Param("updatedAt") Instant updatedAt);

    @Update("UPDATE tasks SET owner_id = #{toOwner} WHERE owner_id = #{fromOwner}")
    int moveTasks(@Param("fromOwner") String fromOwner, @Param("toOwner") String toOwner);

    @Update("UPDATE assets SET owner_id = #{toOwner} WHERE owner_id = #{fromOwner}")
    int moveAssets(@Param("fromOwner") String fromOwner, @Param("toOwner") String toOwner);

    @Delete("""
            DELETE FROM identities WHERE id = #{id}
            AND NOT EXISTS (SELECT 1 FROM tasks WHERE owner_id = #{id})
            AND NOT EXISTS (SELECT 1 FROM assets WHERE owner_id = #{id})
            AND NOT EXISTS (SELECT 1 FROM accounts WHERE identity_id = #{id})
            """)
    int deleteIdentityIfEmpty(@Param("id") String id);

    @Select("""
            <script>
            SELECT * FROM templates
            <if test="category != null">WHERE category = #{category}</if>
            ORDER BY sort_order, id
            </script>
            """)
    List<TemplateRow> listTemplates(@Param("category") String category);

    @Select("SELECT * FROM templates WHERE id = #{id}")
    TemplateRow findTemplate(@Param("id") String id);

    @Select("SELECT category FROM templates GROUP BY category ORDER BY MIN(sort_order)")
    List<String> listCategories();

    @Insert("""
            INSERT INTO assets(id, owner_id, template_id, original_name, mime_type, size_bytes, file_name, created_at)
            VALUES(#{id}, #{ownerId}, #{templateId}, #{originalName}, #{mimeType}, #{sizeBytes}, #{fileName}, #{createdAt})
            """)
    void insertAsset(AssetRow asset);

    @Select("SELECT * FROM assets WHERE id = #{id} AND owner_id = #{ownerId}")
    AssetRow findOwnedAsset(@Param("id") String id, @Param("ownerId") String ownerId);

    @Select("""
            SELECT a.* FROM assets a JOIN task_assets ta ON a.id = ta.asset_id
            JOIN tasks t ON t.id = ta.task_id AND t.owner_id = a.owner_id
            WHERE ta.task_id = #{taskId} ORDER BY ta.position_index LIMIT 1
            """)
    AssetRow firstTaskAsset(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO tasks(id, owner_id, template_id, template_title, template_cover_url,
                status, created_at, updated_at, fail_stage, step_delay_ms)
            VALUES(#{id}, #{ownerId}, #{templateId}, #{templateTitle}, #{templateCoverUrl},
                #{status}, #{createdAt}, #{updatedAt}, #{failStage}, #{stepDelayMs})
            """)
    void insertTask(TaskRow task);

    @Insert("""
            INSERT INTO task_assets(task_id, asset_id, position_index)
            VALUES(#{taskId}, #{assetId}, #{position})
            """)
    void linkAsset(@Param("taskId") String taskId, @Param("assetId") String assetId,
                   @Param("position") int position);

    @Select("SELECT * FROM tasks WHERE id = #{id} AND owner_id = #{ownerId}")
    TaskRow findOwnedTask(@Param("id") String id, @Param("ownerId") String ownerId);

    @Select("SELECT * FROM tasks WHERE owner_id = #{ownerId} ORDER BY created_at DESC, id DESC")
    List<TaskRow> listTasks(@Param("ownerId") String ownerId);

    @Select("SELECT * FROM tasks WHERE id = #{id} FOR UPDATE")
    TaskRow lockTask(@Param("id") String id);

    @Select("SELECT * FROM task_events WHERE task_id = #{taskId} ORDER BY stage_order")
    List<EventRow> events(@Param("taskId") String taskId);

    @Update("""
            UPDATE tasks SET status = #{status}, current_stage = #{currentStage}, updated_at = #{updatedAt},
                result_file_name = #{resultFileName}, result_mime_type = #{resultMimeType},
                failure_reason = #{failureReason}
            WHERE id = #{id}
            """)
    void updateTask(TaskRow task);

    @Insert("""
            INSERT INTO task_events(task_id, stage, stage_order, status, started_at)
            VALUES(#{taskId}, #{stage}, #{order}, 'RUNNING', #{now})
            """)
    void startEvent(@Param("taskId") String taskId, @Param("stage") Stage stage,
                    @Param("order") int order, @Param("now") Instant now);

    @Update("""
            UPDATE task_events SET status = #{status}, finished_at = #{now}
            WHERE task_id = #{taskId} AND stage = #{stage} AND status = 'RUNNING'
            """)
    void finishEvent(@Param("taskId") String taskId, @Param("stage") Stage stage,
                     @Param("status") EventStatus status, @Param("now") Instant now);

    @Update("""
            UPDATE task_events SET status = 'FAILED', finished_at = #{now}
            WHERE status = 'RUNNING' AND task_id IN (SELECT id FROM tasks WHERE status IN ('QUEUED', 'RUNNING'))
            """)
    void failInterruptedEvents(@Param("now") Instant now);

    @Update("""
            UPDATE tasks SET status = 'FAILED', failure_reason = #{reason}, updated_at = #{now}
            WHERE status IN ('QUEUED', 'RUNNING')
            """)
    int failInterruptedTasks(@Param("now") Instant now, @Param("reason") String reason);
}
