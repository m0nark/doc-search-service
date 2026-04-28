package com.docsearch.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;
import org.springframework.data.elasticsearch.annotations.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Document(indexName = "documents")
@Setting(settingPath = "elasticsearch/index-settings.json")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndex {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String tenantId;

    @MultiField(
        mainField = @Field(type = FieldType.Text, analyzer = "standard"),
        otherFields = {
            @InnerField(suffix = "keyword", type = FieldType.Keyword),
            @InnerField(suffix = "suggest", type = FieldType.Search_As_You_Type)
        }
    )
    private String title;

    @Field(type = FieldType.Text, analyzer = "english", termVector = TermVector.with_positions_offsets)
    private String content;

    @Field(type = FieldType.Keyword)
    private String author;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private List<String> tags;

    @Field(type = FieldType.Object, enabled = false)
    private Map<String, String> metadata;

    @Field(type = FieldType.Date)
    private Instant createdAt;

    @Field(type = FieldType.Date)
    private Instant updatedAt;

    @Field(type = FieldType.Keyword)
    private String status;
}
