import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { fetchCourseList } from '@/api/course';
import type { Course } from '@/types';

/**
 * 课程状态
 * 教师端只读：仅支持"展示课程列表 + 切换当前课程"，不提供新增/编辑/删除
 * （TEAM_WORK_DIVISION.md 2.4「课程的新增/编辑不在本期范围内」）。
 */
export const useCourseStore = defineStore('course', () => {
  const courses = ref<Course[]>([]);
  const currentCourseId = ref<number | null>(null);
  const loading = ref(false);

  const currentCourse = computed(
    () => courses.value.find((item) => item.id === currentCourseId.value) ?? null,
  );

  /** 拉取课程列表；默认选中第一门课程作为当前课程 */
  async function loadCourses(): Promise<void> {
    if (loading.value) return;
    loading.value = true;
    try {
      const list = await fetchCourseList();
      courses.value = list;
      if (currentCourseId.value === null && list.length > 0) {
        currentCourseId.value = list[0].id;
      }
    } finally {
      loading.value = false;
    }
  }

  function setCurrentCourse(id: number): void {
    currentCourseId.value = id;
  }

  return { courses, currentCourseId, currentCourse, loading, loadCourses, setCurrentCourse };
});
