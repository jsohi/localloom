package com.localloom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "content_fragments")
public class ContentFragment {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "content_fragment_seq")
    @SequenceGenerator(name = "content_fragment_seq", sequenceName = "content_fragment_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_unit_id", nullable = false)
    private ContentUnit contentUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "fragment_type")
    private FragmentType fragmentType;

    @Column(name = "sequence_index")
    private int sequenceIndex;

    @Column(columnDefinition = "text")
    private String text;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String location;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ContentUnit getContentUnit() {
        return contentUnit;
    }

    public void setContentUnit(ContentUnit contentUnit) {
        this.contentUnit = contentUnit;
    }

    public FragmentType getFragmentType() {
        return fragmentType;
    }

    public void setFragmentType(FragmentType fragmentType) {
        this.fragmentType = fragmentType;
    }

    public int getSequenceIndex() {
        return sequenceIndex;
    }

    public void setSequenceIndex(int sequenceIndex) {
        this.sequenceIndex = sequenceIndex;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
