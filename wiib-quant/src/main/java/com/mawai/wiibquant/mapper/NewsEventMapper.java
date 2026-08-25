package com.mawai.wiibquant.mapper;

import com.mawai.wiibcommon.dto.NewsEventItem;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 快讯打标存档读写。刻意不建 entity 不继承 BaseMapper：写路就一条 insert，
 * 查询按前端契约投影到 DTO，参数直进直出。
 */
@Mapper
public interface NewsEventMapper {

    /** 译文投影行：取用侧拿缓存里的快讯（source_id 对齐）去换字用 */
    @Data
    class Translation {
        private Long sourceId;
        /** 英文标题；null=没译成，回落中文原文 */
        private String titleEn;
        /** 英文正文；null 同上 */
        private String contentEn;
    }

    /** 冲突静默跳过：source_id 唯一键兜底并发与重复窗口，插了多少条以返回值为准。 */
    @Insert("""
            INSERT INTO news_event (source_id, title, content, title_en, content_en,
                                    url, published_at, tags, tagged_model)
            VALUES (#{sourceId}, #{title}, #{content}, #{titleEn}, #{contentEn},
                    #{url}, #{publishedAt}, #{tags}, #{taggedModel})
            ON CONFLICT (source_id) DO NOTHING
            """)
    int insertIgnore(@Param("sourceId") long sourceId, @Param("title") String title,
                     @Param("content") String content, @Param("titleEn") String titleEn,
                     @Param("contentEn") String contentEn, @Param("url") String url,
                     @Param("publishedAt") long publishedAt, @Param("tags") String tags,
                     @Param("taggedModel") String taggedModel);

    /** 本批快讯里已入库的那些 id——先筛后打标，别为存量白烧打标调用。 */
    @Select("""
            <script>
            SELECT source_id FROM news_event WHERE source_id IN
            <foreach collection="sourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectExistingSourceIds(@Param("sourceIds") List<Long> sourceIds);

    /**
     * 按 BlockBeats 快讯 id 取译文。取用侧（首页快讯卡 / news_search 预取 / 深研判素材）
     * 拿的是 NewsCache 里的实时快讯，译文只在打标落库那份里，靠 source_id 对上。
     * 没译成的那条列是 NULL，调用方回落中文原文。
     */
    @Select("""
            <script>
            SELECT source_id AS sourceId, title_en AS titleEn, content_en AS contentEn
              FROM news_event WHERE source_id IN
            <foreach collection="sourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Translation> selectTranslations(@Param("sourceIds") List<Long> sourceIds);

    /**
     * 按标签查时间窗内的快讯（K 线图标数据源）。
     * 标签匹配用逗号包夹：tags 是逗号串，裸 LIKE 会让词表未来加了有包含关系的词
     * （如 GOLD 与 GOLDX）时互相误中；包夹后只按完整词命中。
     * 倒序取最近的——窗口超限时牺牲的是最老的图标。
     * <p>
     * 取哪份文本在 SQL 里定：{@code en=true} 优先译文、缺译文回落原文。前端因此少一层判断，
     * 也保证界面看到的与模型读到的是同一份。
     */
    @Select("""
            <script>
            SELECT id,
            <choose>
              <when test="en">
                   COALESCE(NULLIF(title_en, ''), title) AS title,
                   COALESCE(NULLIF(content_en, ''), content) AS content,
              </when>
              <otherwise>
                   title, content,
              </otherwise>
            </choose>
                   url, published_at AS publishedAt, tags
              FROM news_event
             WHERE ',' || tags || ',' LIKE '%,' || #{tag} || ',%'
               AND published_at BETWEEN #{fromMs} AND #{toMs}
             ORDER BY published_at DESC
             LIMIT #{limit}
            </script>
            """)
    List<NewsEventItem> selectByTagInRange(@Param("tag") String tag, @Param("fromMs") long fromMs,
                                           @Param("toMs") long toMs, @Param("limit") int limit,
                                           @Param("en") boolean en);
}
