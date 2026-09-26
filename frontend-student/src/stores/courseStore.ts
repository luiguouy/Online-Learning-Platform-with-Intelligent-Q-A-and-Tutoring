import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import type { Course } from '@/types';

/**
 * 当前选中课程
 *
 * 说明：学生端「课程切换」只切换检索范围（SSE 的 courseId）。
 * 本期 6 张表没有选课关系表，学生可见全部课程（B 回复确认单 Q12）。
 * 选中课程 id 持久化到 localStorage，刷新页面不丢当前课程。
 *
 * C3.1：`coursesError` 记录课程列表最近一次加载的失败原因（空串 = 无失败）。
 * 放在 store 而不是视图局部：工作台（ChatWorkspace）也要靠它区分
 * 「确实没有课程」与「拉课程失败」，否则断网时会显示「还没有问答记录」这类误导性空态。
 */
const COURSE_ID_KEY = 'courseId';

export const useCourseStore = defineStore('course', () => {
  const courses = ref<Course[]>([]);
  const currentCourseId = ref<number>(Number(localStorage.getItem(COURSE_ID_KEY) ?? 0));
  /** 课程列表加载失败原因；空串表示没有失败（加载中/成功都是空串） */
  const coursesError = ref<string>('');

  const currentCourse = computed<Course | undefined>(() =>
    courses.value.find((item) => item.id === currentCourseId.value),
  );

  function setCourses(list: Course[]): void {
    courses.value = list;
    // 首次进入、或原选中课程已不可见时，落到第一门课程
    const stillVisible = list.some((item) => item.id === currentCourseId.value);
    if (!stillVisible) {
      selectCourse(list[0]?.id ?? 0);
    }
  }

  /** 记录/清除课程列表的失败态（C3.1）；成功与重试开始时都传空串清除 */
  function setCoursesError(message: string): void {
    coursesError.value = message;
  }

  function selectCourse(courseId: number): void {
    currentCourseId.value = courseId;
    localStorage.setItem(COURSE_ID_KEY, String(courseId));
  }

  return { courses, currentCourseId, currentCourse, coursesError, setCourses, setCoursesError, selectCourse };
});
