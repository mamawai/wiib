package com.mawai.wiibquant.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 快讯打标存档读写。刻意不建 entity 不继承 BaseMapper：写路就一条 insert，
 * 参数直进直出；查询等 K 线图标那期再按前端契约加。
 */
@Mapper
public interface NewsEventMapper {

    /** 冲突静默跳过：source_id 唯一键兜底并发与重复窗口，插了多少条以返回值为准。 */
    @Insert("""
            INSERT INTO news_event (source_id, title, content, url, published_at, tags, tagged_model)
            VALUES (#{sourceId}, #{title}, #{content}, #{url}, #{publishedAt}, #{tags}, #{taggedModel})
            ON CONFLICT (source_id) DO NOTHING
            """)
    int insertIgnore(@Param("sourceId") long sourceId, @Param("title") String title,
                     @Param("content") String content, @Param("url") String url,
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
}
